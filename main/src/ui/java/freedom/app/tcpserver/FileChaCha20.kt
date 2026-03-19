package freedom.app.tcpserver

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ChaCha20-Poly1305 file encryption.
 * A fresh random 32-byte key is generated per file transfer and sent to the
 * receiver over the already-encrypted OTP message channel.
 */
object FileChaCha20 {
    const val KEY_BYTES = 32
    private const val NONCE_BYTES = 12

    init {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    fun generateKey(): ByteArray =
        ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }

    /**
     * Deterministic 12-byte nonce from fileId + chunkIdx.
     * Safe because the key is unique per file and chunkIdx is unique within a file.
     */
    fun deriveNonce(fileId: String, chunkIdx: Int): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(fileId.toByteArray(Charsets.UTF_8))
        md.update(
            byteArrayOf(
                (chunkIdx shr 24).toByte(),
                (chunkIdx shr 16).toByte(),
                (chunkIdx shr 8).toByte(),
                chunkIdx.toByte()
            )
        )
        return md.digest().copyOf(NONCE_BYTES)
    }

    fun encrypt(plaintext: ByteArray, key: ByteArray, fileId: String, chunkIdx: Int): ByteArray {
        val nonce = deriveNonce(fileId, chunkIdx)
        val cipher = Cipher.getInstance("ChaCha20-Poly1305", "BC")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        return cipher.doFinal(plaintext)
    }

    fun decrypt(cipherWithTag: ByteArray, key: ByteArray, fileId: String, chunkIdx: Int): ByteArray? {
        return try {
            val nonce = deriveNonce(fileId, chunkIdx)
            val cipher = Cipher.getInstance("ChaCha20-Poly1305", "BC")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
            cipher.doFinal(cipherWithTag)
        } catch (e: Exception) {
            null
        }
    }
}
