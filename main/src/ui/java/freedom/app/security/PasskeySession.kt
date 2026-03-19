package freedom.app.security

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Holds the PBKDF2-derived AES-256 key in memory for the lifetime of the app process.
 *
 * - The passkey itself is NEVER stored. Only the PBKDF2 salt + a GCM verifier
 *   blob are persisted to SharedPreferences.
 * - The derived key is stored as a [ByteArray] (not String) so it can be
 *   zeroed with [lock] when the session should end.
 * - [encryptField] / [decryptField] protect individual ContactData columns
 *   (handshakeKey, otpKey) without encrypting the whole database.
 *
 * Memory safety note:
 *   ByteArray fields can be zeroed; String objects cannot.  Android's
 *   SELinux sandbox prevents other apps from reading this process's heap.
 *   The key lives in memory only while the app process is running; it is
 *   gone when the process is killed.  [lock] zeros it proactively (call
 *   from Activity.onStop if you want to require re-entry on resume).
 */
object PasskeySession {

    private const val PREFS        = "passkey_session"
    private const val KEY_SALT     = "salt"
    private const val KEY_VERIFIER = "verifier"
    private const val VERIFY_PLAIN = "FREEDOM_PASSKEY_V1"

    private const val PBKDF2_ITER  = 200_000
    private const val KEY_BITS     = 256
    private const val SALT_BYTES   = 32
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    /** Minimum number of characters required for a passkey. */
    const val MIN_PASSKEY_LENGTH = 12

    @Volatile private var keyBytes: ByteArray? = null

    val isUnlocked: Boolean get() = keyBytes != null

    fun isPasskeySet(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY_SALT)

    /**
     * First-time setup. Generates a random salt, derives the AES key, stores
     * salt + verifier, and unlocks the session.
     * Run on Dispatchers.IO — PBKDF2 at 200k iterations takes ~200–400 ms.
     */
    fun setup(context: Context, passkey: String) {
        val salt     = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val derived  = derive(passkey, salt)
        val verifier = aesGcmEncrypt(VERIFY_PLAIN.toByteArray(Charsets.UTF_8), derived)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SALT,     Base64.encodeToString(salt,     Base64.NO_WRAP))
            .putString(KEY_VERIFIER, Base64.encodeToString(verifier, Base64.NO_WRAP))
            .apply()
        keyBytes = derived
    }

    /**
     * Unlock on subsequent launches. Returns true if the passkey is correct.
     * Run on Dispatchers.IO.
     */
    fun unlock(context: Context, passkey: String): Boolean {
        val prefs    = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saltB64  = prefs.getString(KEY_SALT,     null) ?: return false
        val verB64   = prefs.getString(KEY_VERIFIER, null) ?: return false
        val salt     = Base64.decode(saltB64, Base64.NO_WRAP)
        val verifier = Base64.decode(verB64,  Base64.NO_WRAP)
        val derived  = derive(passkey, salt)
        return try {
            val plain = String(aesGcmDecrypt(verifier, derived), Charsets.UTF_8)
            if (plain == VERIFY_PLAIN) {
                keyBytes = derived
                true
            } else {
                derived.fill(0)
                false
            }
        } catch (_: Exception) {
            derived.fill(0)
            false
        }
    }

    /** Zero and discard the in-memory AES key. */
    fun lock() {
        keyBytes?.fill(0)
        keyBytes = null
    }

    /**
     * Encrypt a field value with the session key.
     * - Empty string is returned unchanged (preserves DB emptiness checks).
     * - Returns null if the session is locked (call [unlock] first).
     */
    fun encryptField(plaintext: String): String? {
        if (plaintext.isEmpty()) return ""
        val k = keyBytes ?: return null
        return Base64.encodeToString(
            aesGcmEncrypt(plaintext.toByteArray(Charsets.UTF_8), k),
            Base64.NO_WRAP
        )
    }

    /**
     * Decrypt a field value.
     * - Empty string is returned unchanged.
     * - Returns null if the session is locked.
     * - If AES-GCM decryption fails (legacy plaintext from before encryption
     *   was introduced), returns [encoded] unchanged so old contacts keep
     *   working until their key is next persisted with encryption.
     */
    fun decryptField(encoded: String): String? {
        if (encoded.isEmpty()) return ""
        val k = keyBytes ?: return null
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            String(aesGcmDecrypt(bytes, k), Charsets.UTF_8)
        } catch (_: Exception) {
            encoded  // legacy plaintext fallback
        }
    }

    // ── Crypto primitives ─────────────────────────────────────────────────────

    private fun derive(passkey: String, salt: ByteArray): ByteArray {
        val spec    = PBEKeySpec(passkey.toCharArray(), salt, PBKDF2_ITER, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val bytes   = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return bytes
    }

    private fun aesGcmEncrypt(data: ByteArray, key: ByteArray): ByteArray {
        val iv     = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return iv + cipher.doFinal(data)
    }

    private fun aesGcmDecrypt(data: ByteArray, key: ByteArray): ByteArray {
        val iv         = data.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = data.copyOfRange(GCM_IV_BYTES, data.size)
        val cipher     = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }
}
