package net.typeblog.shelter.profile

import android.content.Context
import android.content.Intent
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.abs

/**
 * Cross-profile intent authentication using a shared HMAC-SHA256 secret.
 *
 * The secret is shared across the two profiles, so it CANNOT live in the
 * Android Keystore (each profile has its own Keystore, so a Keystore key is
 * never visible on the other side). Instead:
 *
 * 1. A random 256-bit secret is bootstrapped across the boundary: the side
 *    that has no key yet generates one and sends it verbatim via the
 *    `auth_key` extra (TOFU — the FIRST received key is trusted, later ones
 *    ignored).
 * 2. At rest, that secret is encrypted with a profile-local Android Keystore
 *    AES-GCM wrapping key, so its bytes are only recoverable inside this
 *    profile while remaining tamper-resistant on disk.
 * 3. Steady-state intents carry an HMAC-SHA256 over a canonical payload:
 *    protocol version, action, timestamp, nonce and the remaining extras.
 *    Expired, replayed, malformed or unsigned-but-not-whitelisted intents are
 *    rejected.
 *
 * A one-shot same-process bypass exists for the three original same-process
 * actions (INSTALL_PACKAGE, UNINSTALL_PACKAGE, UNFREEZE_AND_LAUNCH). It is a
 * process-local random token bound to the exact action and a digest of the
 * intent's primitive extras, valid for at most 5 seconds and consumed
 * atomically on first use — never a bare timestamp/boolean global grant.
 */
class AuthManager(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // In-memory caches keyed to the stored secret; invalidated by reset().
    private var cachedSecret: ByteArray? = null
    private val seenNonces = LinkedHashMap<String, Boolean>()

    // Provisioning-authorization gate: whether this profile has entered an
    // explicit setup/provisioning state and may therefore accept a TOFU
    // bootstrap key or a FINALIZE_PROVISION. Set only from provisioning
    // entry points (BIND_DEVICE_ADMIN-protected or the setup wizard).
    private var cachedBootstrapAuth: Boolean? = null
    private val seenBootstrapNonces = LinkedHashSet<String>()

    companion object {
        private const val PREFS_NAME = "shelter_auth"
        private const val PREF_AUTH_KEY = "auth_key"
        private const val PREF_BOOTSTRAP_AUTH = "bootstrap_authorized"

        // Android Keystore wrapping key (profile-local, never crosses the boundary).
        private const val WRAP_ALIAS = "shelter_auth_wrap_key"

        private const val PROTOCOL_VERSION = 1
        private const val SIGNATURE_WINDOW_MS = 30_000L // 30 seconds validity
        private const val BOOTSTRAP_WINDOW_MS = 60_000L  // 60s to complete a bootstrap exchange
        private const val NONCE_CACHE_CAP = 1024

// Intent extras for the signed path. SIGNATURE/NONCE/AUTH_KEY are public so
        // receiver-side gates (e.g. FileShuttle binder handoff) can require
        // steady-state HMAC presence and reject TOFU-seeded intents without
        // duplicating string literals.
        const val EXTRA_AUTH_KEY = "auth_key"
        const val EXTRA_VERSION = "auth_version"
        const val EXTRA_TIMESTAMP = "timestamp"
        const val EXTRA_NONCE = "auth_nonce"
        const val EXTRA_SIGNATURE = "signature"
        private const val EXTRA_BOOTSTRAP_NONCE = "bootstrap_nonce"

        // Explicit marker an intent must carry to be allowed to trigger a TOFU
        // bootstrap exchange or finalization. Set by signIntent() bootstrap
        // emission and by the finalize senders (FinalizeActivity /
        // DeviceAdminReceiver); combined with the persisted provisioning gate.
        const val EXTRA_BOOTSTRAP_ALLOWED = "bootstrap_allowed"

        // Extras excluded from the canonical payload and from delivering a fresh key.
        private val RESERVED_EXTRAS = setOf(
            EXTRA_AUTH_KEY, EXTRA_VERSION, EXTRA_TIMESTAMP,
            EXTRA_NONCE, EXTRA_SIGNATURE, EXTRA_BOOTSTRAP_NONCE,
            EXTRA_BOOTSTRAP_ALLOWED,
        )

        // Extras carrying the one-shot same-process token grant; excluded from
        // the token-bound digest so registration-time and check-time digests agree.
        private const val EXTRA_SAME_PROCESS = "is_same_process"
        private const val EXTRA_SAME_PROCESS_TOKEN = "is_same_process_token"
        private val SAME_PROCESS_EXCLUDED_EXTRAS =
            RESERVED_EXTRAS + setOf(EXTRA_SAME_PROCESS, EXTRA_SAME_PROCESS_TOKEN)

        // One-shot same-process token registry (process-local, like the original
        // bypass). A grant is a random token bound to the exact action and a
        // digest of the intent's primitive extras; it expires after 5 seconds
        // and is consumed atomically on first verification.
        private data class SameProcessGrant(
            val action: String,
            val digest: String,
            val expiresAt: Long,
        )
        private const val SAME_PROCESS_WINDOW_MS = 5_000L
        private const val SAME_PROCESS_GRANT_CAP = 16
        private val sameProcessGrants = LinkedHashMap<String, SameProcessGrant>()

        // Actions that may be authenticated via a bootstrap exchange. Everything
        // requiring a shared secret is eligible except the public-entry actions
        // and the PackageInstaller callback (which uses its own session nonce).
        private val BOOTSTRAP_ACTIONS: Set<String> by lazy {
            val allSigned = Actions.SAME_PROCESS_ACTIONS +
                listOf(
                    Actions.START_SERVICE,
                    Actions.TRY_START_SERVICE,
                    Actions.START_FILE_SHUTTLE,
                    Actions.START_FILE_SHUTTLE_2,
                    Actions.UNFREEZE_AND_LAUNCH,
                    Actions.PUBLIC_FREEZE_ALL,
                    Actions.PUBLIC_UNFREEZE_AND_LAUNCH,
                    Actions.FREEZE_ALL_IN_LIST,
                    Actions.SYNCHRONIZE_PREFERENCE,
                )
            allSigned - Actions.UNSIGNED_ACTIONS
        }

        private val random = SecureRandom()
    }

    // ------------------------------------------------------------------ helpers

    private fun getOrCreateWrapKey() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(WRAP_ALIAS)) return
        val kg = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(
                WRAP_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        kg.generateKey()
    }

    private fun wrapSecret(secret: ByteArray): String {
        getOrCreateWrapKey()
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = (ks.getEntry(WRAP_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ct = cipher.doFinal(secret)
        // iv || ciphertext
        val out = ByteArray(cipher.iv.size + ct.size)
        System.arraycopy(cipher.iv, 0, out, 0, cipher.iv.size)
        System.arraycopy(ct, 0, out, cipher.iv.size, ct.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun unwrapSecret(encoded: String): ByteArray {
        val raw = Base64.decode(encoded, Base64.NO_WRAP)
        val ivSize = 12
        if (raw.size < ivSize + 16) throw IllegalArgumentException("blob too short")
        val iv = raw.copyOfRange(0, ivSize)
        val ct = raw.copyOfRange(ivSize, raw.size)
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = (ks.getEntry(WRAP_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(ct)
    }

    /** Decrypted shared secret, or null if none has been bootstrapped yet. */
    private fun sharedSecret(): ByteArray? {
        cachedSecret?.let { return it }
        val encoded = prefs.getString(PREF_AUTH_KEY, null) ?: return null
        return try {
            unwrapSecret(encoded).also { cachedSecret = it }
        } catch (_: Exception) {
            // Corrupt/unwrap-able stored blob: force a fresh bootstrap.
            null
        }
    }

    private fun storeSecret(secret: ByteArray) {
        prefs.edit().putString(PREF_AUTH_KEY, wrapSecret(secret)).apply()
        cachedSecret = secret
        seenNonces.clear()
    }

    private fun hmac(secret: ByteArray, payload: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8))
    }

    private fun buildPayload(version: Int, action: String, ts: Long,
                             nonce: String, extras: String): String =
        "v$version|$action|$ts|$nonce|$extras"

    /**
     * Canonical, sorted key=value representation of the non-reserved extras,
     * so that signing and verifying agree regardless of Bundle ordering.
     */
    private fun canonicalExtras(intent: Intent): String {
        val extras = intent.extras ?: return ""
        val parts = ArrayList<String>()
        for (key in extras.keySet()) {
            if (key in RESERVED_EXTRAS) continue
            val v = extras.get(key) ?: continue
            // Only primitive-ish extras participate; guards skip addresses/blobs.
            when (v) {
                is String -> parts += "$key=$v"
                is Int -> parts += "$key=$v"
                is Long -> parts += "$key=$v"
                is Boolean -> parts += "$key=$v"
                is Float -> parts += "$key=$v"
                is Double -> parts += "$key=$v"
                is Array<*> -> parts += "$key=${v.joinToString(",")}"
            }
        }
        parts.sort()
        return parts.joinToString("&")
    }

    // ------------------------------------------------------------------ public API

    /**
     * Register that [intent] will be delivered to the counterpart same-process
     * activity without a signature. Arms a process-local one-shot random token
     * bound to the exact action and a digest of the intent's primitive extras,
     * stamps the marker extras onto [intent], and returns the token. The grant
     * is valid for at most 5 seconds and is consumed atomically by
     * [verifySameProcess]. Process-local and unpredictable, so a cross-process
     * attacker cannot fabricate the bypass.
     */
    @JvmStatic
    @Synchronized
    fun registerSameProcess(intent: Intent): String {
        // Bound the registry: drop everything on overflow so a buggy caller
        // cannot grow it without limit (the newest grant always survives).
        if (sameProcessGrants.size >= SAME_PROCESS_GRANT_CAP) sameProcessGrants.clear()
        sameProcessGrants.entries.removeAll { it.value.expiresAt <= System.currentTimeMillis() }
        val now = System.currentTimeMillis()
        val token = java.util.UUID.randomUUID().toString()
        sameProcessGrants[token] = SameProcessGrant(
            action = intent.action ?: "",
            digest = sameProcessDigest(intent),
            expiresAt = now + SAME_PROCESS_WINDOW_MS,
        )
        intent.putExtra(EXTRA_SAME_PROCESS, true)
        intent.putExtra(EXTRA_SAME_PROCESS_TOKEN, token)
        return token
    }

    /**
     * Mark this profile as being in an explicit provisioning/bootstrap state,
     * authorizing a single TOFU key exchange and FINALIZE_PROVISION acceptance.
     * Called only from provisioning entry points (the setup wizard and the
     * BIND_DEVICE_ADMIN-protected finalize receiver/activity).
     */
    fun markBootstrapAuthorized() {
        prefs.edit().putBoolean(PREF_BOOTSTRAP_AUTH, true).apply()
        cachedBootstrapAuth = true
        seenBootstrapNonces.clear()
    }

    /** Whether this profile has entered (and not yet reset) provisioning state. */
    fun isBootstrapAuthorized(): Boolean {
        cachedBootstrapAuth?.let { return it }
        return prefs.getBoolean(PREF_BOOTSTRAP_AUTH, false).also { cachedBootstrapAuth = it }
    }

    /**
     * Close the provisioning/bootstrap gate for good. Called once a shared
     * secret provably exists in this profile — a TOFU key accepted from the
     * counterpart or a steady-state HMAC verified — so the TOFU and
     * FINALIZE_PROVISION acceptance windows do not stay open indefinitely
     * after setup. A past provisioning state must never keep accepting
     * key/finalize injection from a later same-profile attacker.
     */
    private fun disarmBootstrapAuth() {
        cachedBootstrapAuth = false
        prefs.edit().remove(PREF_BOOTSTRAP_AUTH).apply()
        seenBootstrapNonces.clear()
    }

    /**
     * Sign an intent for cross-profile transfer. If we have no shared secret
     * yet, bootstrap one — but ONLY while provisioning is authorized, and only
     * for an action that actually requires a shared secret. Otherwise the
     * intent stays unsigned and is rejected by the receiver (fail closed).
     */
    fun signIntent(intent: Intent): Intent = intent.apply {
        val secret = sharedSecret()
        if (secret == null) {
            val action = intent.action
            if (isBootstrapAuthorized() && action != null && action in BOOTSTRAP_ACTIONS) {
                val fresh = ByteArray(32).also { random.nextBytes(it) }
                putExtra(EXTRA_AUTH_KEY, Base64.encodeToString(fresh, Base64.NO_WRAP))
                putExtra(EXTRA_VERSION, PROTOCOL_VERSION)
                putExtra(EXTRA_BOOTSTRAP_NONCE, java.util.UUID.randomUUID().toString())
                putExtra(EXTRA_BOOTSTRAP_ALLOWED, true)
                putExtra(EXTRA_TIMESTAMP, System.currentTimeMillis())
                storeSecret(fresh)
            }
            // Not authorized (or not a bootstrapable action): fail closed by
            // leaving the intent unsigned; the receiver will reject it.
        } else {
            val action = intent.action
            if (action != null) {
                val version = PROTOCOL_VERSION
                val ts = System.currentTimeMillis()
                val nonce = java.util.UUID.randomUUID().toString()
                putExtra(EXTRA_VERSION, version)
                putExtra(EXTRA_TIMESTAMP, ts)
                putExtra(EXTRA_NONCE, nonce)
                putExtra(EXTRA_SIGNATURE,
                    hmac(secret, buildPayload(version, action, ts, nonce, canonicalExtras(intent))))
            }
        }
    }

    /**
     * Verify an intent received across the profile boundary.
     * Returns true for whitelisted unsigned actions, the one-shot same-process
     * bypass (for its three actions), a fresh TOFU bootstrap key, or a valid
     * signed intent.
     */
    fun verifyIntent(intent: Intent): Boolean {
        val action = intent.action ?: return false

        // FINALIZE_PROVISION mutates provisioning state. It is NOT allowed to
        // ride the generic unsigned path: it requires BOTH the explicit
        // bootstrap_allowed marker (bootstrap emission sets it via signIntent;
        // the finalize senders must set it explicitly) AND an armed persisted
        // provisioning state. That state is armed only by provisioning entry
        // points — the BIND_DEVICE_ADMIN-protected FinalizeActivity and
        // DeviceAdminReceiver (invoked solely by the provisioning framework)
        // and the non-exported SetupActivity — so an arbitrary app can neither
        // arm it nor forge a finalize. The marker is disarmed as soon as a
        // shared secret provably exists (see [disarmBootstrapAuth]).
        if (action == Actions.FINALIZE_PROVISION) {
            return isBootstrapAuthorized() &&
                intent.getBooleanExtra(EXTRA_BOOTSTRAP_ALLOWED, false)
        }

        // Whitelisted public actions need no signature.
        if (action in Actions.UNSIGNED_ACTIONS) return true

        // One-shot same-process token bypass (original three actions only).
        if (verifySameProcess(intent)) return true

        val secret = sharedSecret()
        if (secret == null) {
            // TOFU bootstrap: accept the FIRST delivered key only when (a) this
            // profile is in an explicit provisioning state, (b) the action is one
            // that genuinely requires a shared secret, and (c) the exchange carries
            // a fresh nonce within the bootstrap window. This blocks arbitrary
            // exported intents from seeding a hostile shared secret.
            if (!isBootstrapAuthorized()) return false
            if (action !in BOOTSTRAP_ACTIONS) return false
            // The sender must have explicitly declared this a bootstrap
            // exchange; a generic unsigned intent must fail closed.
            if (!intent.getBooleanExtra(EXTRA_BOOTSTRAP_ALLOWED, false)) return false

            val delivered = intent.getStringExtra(EXTRA_AUTH_KEY) ?: return false
            if (intent.getIntExtra(EXTRA_VERSION, 0) != PROTOCOL_VERSION) return false

            val nonce = intent.getStringExtra(EXTRA_BOOTSTRAP_NONCE) ?: return false
            if (nonce.isEmpty() || !seenBootstrapNonces.add(nonce)) return false

            val ts = intent.getLongExtra(EXTRA_TIMESTAMP, 0L)
            val now = System.currentTimeMillis()
            if (ts <= 0L) return false
            if (now - ts > BOOTSTRAP_WINDOW_MS) return false       // expired
            if (abs(now - ts) > BOOTSTRAP_WINDOW_MS) return false  // future skew

            val decoded = runCatching {
                Base64.decode(delivered, Base64.NO_WRAP)
            }.getOrNull() ?: return false
            if (decoded.size != 32) return false
            storeSecret(decoded)
            // A key exchange completed: the provisioning/bootstrap window is
            // over. Accepting the counterpart's key once is enough — future
            // intents ride the HMAC path, and a past setup state must not keep
            // accepting later key/finalize injection.
            disarmBootstrapAuth()
            return true
        }

        // Steady state: reject malformed, expired, replayed or forged intents.
        val version = intent.getIntExtra(EXTRA_VERSION, Int.MIN_VALUE)
        if (version != PROTOCOL_VERSION) return false

        val ts = intent.getLongExtra(EXTRA_TIMESTAMP, 0L)
        if (ts <= 0L) return false

        val now = System.currentTimeMillis()
        if (now - ts > SIGNATURE_WINDOW_MS) return false       // expired
        if (abs(now - ts) > SIGNATURE_WINDOW_MS) return false  // future skew

        val nonce = intent.getStringExtra(EXTRA_NONCE) ?: return false
        val nonceKey = "${System.identityHashCode(secret) and 0xFFFF}:$nonce"
        if (seenNonces.containsKey(nonceKey)) return false      // replay
        if (seenNonces.size >= NONCE_CACHE_CAP) seenNonces.clear()

        val signature = intent.getByteArrayExtra(EXTRA_SIGNATURE) ?: return false

        val expected = hmac(
            secret,
            buildPayload(version, action, ts, nonce, canonicalExtras(intent)))
        if (!MessageDigest.isEqual(signature, expected)) return false

        seenNonces[nonceKey] = true
        // A valid signed exchange provably crosses the boundary; the shared
        // secret is in place, so the provisioning/bootstrap gate is over.
        disarmBootstrapAuth()
        return true
    }

    /**
     * Atomically verify and consume a same-process token grant carried by
     * [intent]. Accepts only the original same-process actions, requires the
     * marker extras, a live grant whose action and primitive-extras digest
     * match, and consumes the grant on success (one-shot) and on any failing
     * match (fail closed — no retry with the same token).
     */
    @JvmStatic
    @Synchronized
    fun verifySameProcess(intent: Intent): Boolean {
        val action = intent.action ?: return false
        if (action !in Actions.SAME_PROCESS_ACTIONS) return false
        if (!intent.getBooleanExtra(EXTRA_SAME_PROCESS, false)) return false
        val token = intent.getStringExtra(EXTRA_SAME_PROCESS_TOKEN)
        if (token.isNullOrEmpty()) return false

        val grant = sameProcessGrants[token] ?: return false
        sameProcessGrants.remove(token) // consume: one-shot, no retry
        if (System.currentTimeMillis() > grant.expiresAt) return false
        if (grant.action != action) return false
        if (grant.digest != sameProcessDigest(intent)) return false
        return true
    }

    /**
     * Canonical primitive action+extras fingerprint (sorted key=value), with
     * the marker extras and the HMAC/bootstrap extras excluded so that
     * registration-time and check-time digests agree.
     */
    private fun sameProcessDigest(intent: Intent): String {
        val sb = StringBuilder(intent.action ?: "")
        val extras = intent.extras
        if (extras != null) {
            val parts = ArrayList<String>()
            for (key in extras.keySet()) {
                if (key in SAME_PROCESS_EXCLUDED_EXTRAS) continue
                val v = extras.get(key) ?: continue
                when (v) {
                    is String -> parts += "$key=$v"
                    is Int -> parts += "$key=$v"
                    is Long -> parts += "$key=$v"
                    is Boolean -> parts += "$key=$v"
                    is Float -> parts += "$key=$v"
                    is Double -> parts += "$key=$v"
                    is Array<*> -> parts += "$key=${v.joinToString(",")}"
                }
            }
            parts.sort()
            sb.append('&').append(parts.joinToString("&"))
        }
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(sb.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** Forget the shared secret and reset local state (called at setup). */
    fun reset() {
        cachedSecret = null
        seenNonces.clear()
        seenBootstrapNonces.clear()
        cachedBootstrapAuth = null
        sameProcessGrants.clear()
        prefs.edit().remove(PREF_AUTH_KEY).remove(PREF_BOOTSTRAP_AUTH).apply()
    }
}
