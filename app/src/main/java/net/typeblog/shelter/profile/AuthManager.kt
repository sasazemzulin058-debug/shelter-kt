package net.typeblog.shelter.profile

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
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
 * 1. The 256-bit secret is bootstrapped by the SETUP WIZARD, not by any
 *    intent: SetupActivity generates one random secret, installs it in the
 *    parent profile, and places the identical bytes in the platform
 *    provisioning admin extras bundle
 *    ([DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE]). The
 *    provisioning framework delivers that bundle to the trusted admin entry
 *    points in the managed profile — FinalizeActivity on O+,
 *    DeviceAdminReceiver before O — which install it via
 *    [installProvisionedSecret]. No `auth_key` intent extra is ever accepted:
 *    an exported intent cannot seed a shared secret (fail closed).
 * 2. At rest, that secret is encrypted with a profile-local Android Keystore
 *    AES-GCM wrapping key, so its bytes are only recoverable inside this
 *    profile while remaining tamper-resistant on disk.
 * 3. Steady-state intents carry an HMAC-SHA256 over a canonical payload:
 *    protocol version, action, timestamp, nonce and the remaining extras.
 *    Expired, replayed, malformed or unsigned-but-not-whitelisted intents are
 *    rejected.
 *
 * FINALIZE_PROVISION mutates provisioning state, so it is NOT a public action.
 * It is accepted only through a process-local one-shot random token registered
 * by the trusted provisioning entry points (FinalizeActivity / the pre-O
 * DeviceAdminReceiver), or — the pre-O parent hop — signed with the shared
 * secret. An arbitrary exported intent carries neither and fails closed.
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

    companion object {
        private const val PREFS_NAME = "shelter_auth"
        private const val PREF_AUTH_KEY = "auth_key"

        // Android Keystore wrapping key (profile-local, never crosses the boundary).
        private const val WRAP_ALIAS = "shelter_auth_wrap_key"

        private const val PROTOCOL_VERSION = 1
        private const val SIGNATURE_WINDOW_MS = 30_000L // 30 seconds validity
        private const val NONCE_CACHE_CAP = 1024

// Intent extras for the signed path. SIGNATURE/NONCE are public so
        // receiver-side gates (e.g. FileShuttle binder handoff) can require
        // steady-state HMAC presence without duplicating string literals.
        const val EXTRA_VERSION = "auth_version"
        const val EXTRA_TIMESTAMP = "timestamp"
        const val EXTRA_NONCE = "auth_nonce"
        const val EXTRA_SIGNATURE = "signature"

        // Key inside the platform provisioning admin extras bundle that carries
        // the 32-byte shared secret from SetupActivity to the managed profile.
        const val EXTRA_PROVISIONED_SECRET = "shelter_provisioned_secret"

        // Process-local one-shot FINALIZE_PROVISION token (set by
        // registerFinalizeProvision, consumed by verifyFinalizeProvision).
        private const val EXTRA_FINALIZE_TOKEN = "finalize_token"

        // Extras excluded from the canonical payload.
        private val RESERVED_EXTRAS = setOf(
            EXTRA_VERSION, EXTRA_TIMESTAMP,
            EXTRA_NONCE, EXTRA_SIGNATURE,
            EXTRA_PROVISIONED_SECRET, EXTRA_FINALIZE_TOKEN,
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

        // One-shot FINALIZE_PROVISION grants (process-local). Registered only
        // by the trusted provisioning entry points — FinalizeActivity (O+) and
        // the pre-O DeviceAdminReceiver — immediately before they start
        // DummyActivity, and consumed atomically by its verification gate. A
        // long window is harmless: the token is a fresh 128-bit random value
        // that cannot be guessed, and registration happens only in
        // BIND_DEVICE_ADMIN-protected code, so a 60-minute validity simply
        // tolerates the pre-O notification-tap delay.
        private const val FINALIZE_WINDOW_MS = 60 * 60_000L
        private const val FINALIZE_GRANT_CAP = 16
        private val finalizeGrants = LinkedHashMap<String, Long>()

        /**
         * Register that [intent] will be delivered to the counterpart same-process
         * activity without a signature. Arms a process-local one-shot random token
         * bound to the exact action and a digest of the intent's primitive extras,
         * stamps the marker extras onto [intent], and returns the token. The grant
         * is valid for at most 5 seconds and is consumed atomically by
         * [verifySameProcess]. Process-local and unpredictable, so a cross-process
         * attacker cannot fabricate the bypass.
         */
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
         * Atomically verify and consume a same-process token grant carried by
         * [intent]. Accepts only the original same-process actions, requires the
         * marker extras, a live grant whose action and primitive-extras digest
         * match, and consumes the grant on success (one-shot) and on any failing
         * match (fail closed — no retry with the same token).
         */
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
         * Register a process-local one-shot grant for a FINALIZE_PROVISION
         * intent that will be delivered to DummyActivity in THIS process. The
         * callers are exclusively the trusted provisioning entry points
         * (FinalizeActivity on O+, DeviceAdminReceiver before O), which are
         * protected by BIND_DEVICE_ADMIN and invoked solely by the provisioning
         * framework; the grant is stamped onto [intent] and consumed atomically
         * by [verifyFinalizeProvision]. An arbitrary exported intent cannot
         * fabricate the token.
         */
        @Synchronized
        fun registerFinalizeProvision(intent: Intent): String {
            if (finalizeGrants.size >= FINALIZE_GRANT_CAP) finalizeGrants.clear()
            finalizeGrants.entries.removeAll { it.value <= System.currentTimeMillis() }
            val token = java.util.UUID.randomUUID().toString()
            finalizeGrants[token] = System.currentTimeMillis() + FINALIZE_WINDOW_MS
            intent.putExtra(EXTRA_FINALIZE_TOKEN, token)
            return token
        }

        /**
         * Atomically verify and consume a FINALIZE_PROVISION grant carried by
         * [intent]. Consumes on any attempt (one-shot, fail closed — no retry).
         */
        @Synchronized
        fun verifyFinalizeProvision(intent: Intent): Boolean {
            if (intent.action != Actions.FINALIZE_PROVISION) return false
            val token = intent.getStringExtra(EXTRA_FINALIZE_TOKEN) ?: return false
            val expiresAt = finalizeGrants.remove(token) ?: return false // consume
            return System.currentTimeMillis() <= expiresAt
        }

        /**
         * Read the 32-byte shared secret out of the platform provisioning admin
         * extras bundle of [intent] (SetupActivity placed it there before
         * launching [DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE]).
         * Returns null when the platform did not deliver the bundle or the
         * secret is missing/malformed — the caller must fail closed.
         */
        fun provisionedSecret(intent: Intent?): ByteArray? {
            val bundle = intent
                ?.getParcelableExtra<PersistableBundle>(
                    DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
                )
                ?: return null
            val secret = bundle.getByteArray(EXTRA_PROVISIONED_SECRET) ?: return null
            return if (secret.size == 32) secret else null
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
            val md = MessageDigest.getInstance("SHA-256")
            return md.digest(sb.toString().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
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
            // Corrupt/unwrap-able stored blob: force the provisioning flow to
            // re-bootstrap; never authenticate with it.
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
     * Install the shared 256-bit secret delivered by the platform provisioning
     * flow (see [provisionedSecret]). Requires exactly 32 bytes; anything else
     * is rejected, so a malformed platform delivery fails closed instead of
     * silently bootstrapping a broken session. Installing the secret closes the
     * setup window: from here on the profile authenticates via steady-state
     * HMAC only.
     */
    fun installProvisionedSecret(secret: ByteArray) {
        require(secret.size == 32) {
            "provisioned secret must be exactly 32 bytes, got ${secret.size}"
        }
        storeSecret(secret)
    }

    /** Whether this profile already holds the shared secret (steady state). */
    fun hasSharedSecret(): Boolean = sharedSecret() != null

    /**
     * Register that [intent] will be delivered to the counterpart same-process
     * activity without a signature. Arms a process-local one-shot random token
     * bound to the exact action and a digest of the intent's primitive extras,
     * stamps the marker extras onto [intent], and returns the token. The grant
     * is valid for at most 5 seconds and is consumed atomically by
     * [verifySameProcess]. Process-local and unpredictable, so a cross-process
     * attacker cannot fabricate the bypass.
     */
    fun registerSameProcess(intent: Intent): String =
        Companion.registerSameProcess(intent)

    /**
     * Register a process-local one-shot FINALIZE_PROVISION grant on an intent
     * about to be delivered to DummyActivity in this process. See the companion
     * [Companion.registerFinalizeProvision].
     */
    fun registerFinalizeProvision(intent: Intent): String =
        Companion.registerFinalizeProvision(intent)

    /**
     * Sign an intent for cross-profile transfer. Requires the shared secret —
     * there is NO bootstrap emission: a secret can only be installed through
     * [installProvisionedSecret] (the platform provisioning admin extras), so
     * an intent sent without a secret stays unsigned and is rejected by the
     * receiver (fail closed).
     */
    fun signIntent(intent: Intent): Intent = intent.apply {
        val secret = sharedSecret() ?: return@apply // fail closed, leave unsigned
        val action = intent.action ?: return@apply
        val version = PROTOCOL_VERSION
        val ts = System.currentTimeMillis()
        val nonce = java.util.UUID.randomUUID().toString()
        putExtra(EXTRA_VERSION, version)
        putExtra(EXTRA_TIMESTAMP, ts)
        putExtra(EXTRA_NONCE, nonce)
        putExtra(EXTRA_SIGNATURE,
            hmac(secret, buildPayload(version, action, ts, nonce, canonicalExtras(intent))))
    }

    /**
     * Verify an intent received across the profile boundary.
     * Returns true for whitelisted unsigned actions, the one-shot same-process
     * bypass (for its three actions), a consumed FINALIZE_PROVISION token or
     * pre-O signed FINALIZE delivery, or a valid signed intent.
     */
    fun verifyIntent(intent: Intent): Boolean {
        val action = intent.action ?: return false

        // FINALIZE_PROVISION mutates provisioning state. It is NOT a public
        // action. It is accepted only when (a) the process-local one-shot token
        // registered by the trusted provisioning entry points (FinalizeActivity
        // on O+, DeviceAdminReceiver before O — both BIND_DEVICE_ADMIN-protected
        // and invoked solely by the provisioning framework) matches and is
        // consumed, or (b) — the pre-O parent hop — it arrives signed with the
        // shared secret, which proves a same-app delivery from the provisioned
        // profile. An arbitrary exported intent carries neither a token nor a
        // valid signature and fails closed.
        if (action == Actions.FINALIZE_PROVISION && verifyFinalizeProvision(intent)) {
            return true
        }

        // Whitelisted public actions need no signature.
        if (action in Actions.UNSIGNED_ACTIONS) return true

        // One-shot same-process token bypass (original three actions only).
        if (verifySameProcess(intent)) return true

        // Steady state: the shared secret must already be installed. There is
        // deliberately NO TOFU path — no `auth_key` extra is ever accepted —
        // so a profile without a secret rejects everything (fail closed).
        val secret = sharedSecret() ?: return false

        // Reject malformed, expired, replayed or forged intents.
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
        return true
    }

    /**
     * Atomically verify and consume a same-process token grant carried by
     * [intent]. Accepts only the original same-process actions, requires the
     * marker extras, a live grant whose action and primitive-extras digest
     * match, and consumes the grant on success (one-shot) and on any failing
     * match (fail closed — no retry with the same token).
     */
    fun verifySameProcess(intent: Intent): Boolean =
        Companion.verifySameProcess(intent)

    /** Forget the shared secret and reset local state (called at setup). */
    fun reset() {
        cachedSecret = null
        seenNonces.clear()
        sameProcessGrants.clear()
        finalizeGrants.clear()
        prefs.edit().remove(PREF_AUTH_KEY).apply()
    }
}