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
 * A one-shot 5-second bypass exists for the three original same-process
 * actions (INSTALL_PACKAGE, UNINSTALL_PACKAGE, UNFREEZE_AND_LAUNCH); it is
 * consumed on first use.
 */
class AuthManager(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // In-memory caches keyed to the stored secret; invalidated by reset().
    private var cachedSecret: ByteArray? = null
    private val seenNonces = LinkedHashMap<String, Boolean>()

    // One-shot same-process bypass state (process-local, like the original).
    @Volatile
    private var bypassUntil: Long = 0L

    @Volatile
    private var bypassConsumed: Boolean = false

    companion object {
        private const val PREFS_NAME = "shelter_auth"
        private const val PREF_AUTH_KEY = "auth_key"

        // Android Keystore wrapping key (profile-local, never crosses the boundary).
        private const val WRAP_ALIAS = "shelter_auth_wrap_key"

        private const val PROTOCOL_VERSION = 1
        private const val SIGNATURE_WINDOW_MS = 30_000L // 30 seconds validity
        private const val NONCE_CACHE_CAP = 1024

        // Intent extras for the signed path.
        private const val EXTRA_AUTH_KEY = "auth_key"
        private const val EXTRA_VERSION = "auth_version"
        private const val EXTRA_TIMESTAMP = "timestamp"
        private const val EXTRA_NONCE = "auth_nonce"
        private const val EXTRA_SIGNATURE = "signature"

        // Extras excluded from the canonical payload and from delivering a fresh key.
        private val RESERVED_EXTRAS = setOf(
            EXTRA_AUTH_KEY, EXTRA_VERSION, EXTRA_TIMESTAMP,
            EXTRA_NONCE, EXTRA_SIGNATURE,
        )

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
     * Register that an intent will be sent to this same process without a
     * signature. Allowed for the next 5 seconds and consumed on first use.
     */
    fun markLocalBypass() {
        bypassUntil = System.currentTimeMillis() + 5_000L
        bypassConsumed = false
    }

    /**
     * Sign an intent for cross-profile transfer. If we have no shared secret
     * yet, bootstrap one and send it verbatim via the `auth_key` extra.
     */
    fun signIntent(intent: Intent): Intent = intent.apply {
        val secret = sharedSecret()
        if (secret == null) {
            val fresh = ByteArray(32).also { random.nextBytes(it) }
            putExtra(EXTRA_AUTH_KEY, Base64.encodeToString(fresh, Base64.NO_WRAP))
            putExtra(EXTRA_VERSION, PROTOCOL_VERSION)
            storeSecret(fresh)
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

        // Whitelisted public actions need no signature.
        if (action in Actions.UNSIGNED_ACTIONS) return true

        // One-shot same-process bypass (original three actions only).
        if (verifySameProcess(action)) return true

        val secret = sharedSecret()
        if (secret == null) {
            // TOFU bootstrap: accept and store the FIRST delivered key only.
            val delivered = intent.getStringExtra(EXTRA_AUTH_KEY)
            if (delivered != null && intent.getIntExtra(EXTRA_VERSION, 0) == PROTOCOL_VERSION) {
                val decoded = runCatching {
                    Base64.decode(delivered, Base64.NO_WRAP)
                }.getOrNull() ?: return false
                if (decoded.size != 32) return false
                storeSecret(decoded)
                return true
            }
            return false
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
        return true
    }

    private fun verifySameProcess(action: String): Boolean {
        if (action !in Actions.SAME_PROCESS_ACTIONS) return false
        if (System.currentTimeMillis() > bypassUntil) return false
        if (bypassConsumed) return false
        // Consume the one-shot grant.
        bypassConsumed = true
        bypassUntil = 0L
        return true
    }

    /** Forget the shared secret and reset local state (called at setup). */
    fun reset() {
        cachedSecret = null
        seenNonces.clear()
        bypassUntil = 0L
        bypassConsumed = true
        prefs.edit().remove(PREF_AUTH_KEY).apply()
    }
}
