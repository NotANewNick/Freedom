package freedom.app.tcpserver

import android.util.Log
import freedom.app.helper.FreedomCrypto

/**
 * Encrypts outgoing messages with [sendKey] and decrypts incoming with [recvKey].
 *
 * Uses cyclic XOR: every message is XOR'd with the full key starting at byte 0.
 * The same key is reused for every message until rotation. This is NOT true OTP —
 * it trades perfect secrecy for simplicity (no offset tracking or state sync).
 *
 * Tracks [messagesSent] to trigger key rotation at [rotationThreshold].
 *
 * Each direction has its own key:
 *   sendKey — encrypts messages WE send
 *   recvKey — decrypts messages WE receive
 *
 * @param contactId  DB id of the remote contact.
 * @param sendKey    Raw bytes of our send key (24 KB).
 * @param recvKey    Raw bytes of our recv key (24 KB).
 */
class OtpChannel(
    val contactId: Long,
    private val sendKey: ByteArray,
    private val recvKey: ByteArray,
    private val rotationThreshold: Int = FreedomCrypto.DEFAULT_ROTATION_THRESHOLD
) {
    /** Messages sent with the current send key (in this session). */
    @Volatile
    var messagesSent: Int = 0
        private set

    /**
     * Encrypt [plaintext] and return a Base64 wire-ready string.
     * Returns null if the send key is empty.
     */
    fun encrypt(plaintext: String): String? {
        if (sendKey.isEmpty()) return null
        return try {
            val result = FreedomCrypto.encrypt(plaintext, sendKey)
            messagesSent++
            result
        } catch (e: Exception) {
            Log.w(TAG, "OTP encrypt failed: ${e.message}")
            null
        }
    }

    /**
     * Decrypt a Base64 wire line produced by the remote side.
     * Returns null if the recv key is empty or decryption fails.
     */
    fun decrypt(ciphertextBase64: String): String? {
        if (recvKey.isEmpty()) return null
        return try {
            FreedomCrypto.decryptToString(ciphertextBase64, recvKey)
        } catch (e: Exception) {
            Log.w(TAG, "OTP decrypt failed: ${e.message}")
            null
        }
    }

    /** True when the send key should be rotated based on message count. */
    fun needsRotation(): Boolean = messagesSent >= rotationThreshold

    companion object {
        private const val TAG = "OtpChannel"
    }
}
