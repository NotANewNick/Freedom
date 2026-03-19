package freedom.app.tcpserver

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import freedom.app.data.entity.MessageData
import freedom.app.viewModels.MessageViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles file send and receive over the persistent TCP connection.
 *
 * All file content is encrypted with [FileChaCha20] — ChaCha20-Poly1305
 * authenticated encryption with a fresh random key per file transfer.
 * The per-file key is delivered to the receiver inside the FILE_START control
 * message, which itself travels over the OTP-encrypted message channel.
 *
 * Control messages (FILE_START, FILE_ACK, FILE_DONE, FILE_ERR) use the regular
 * OtpChannel path via ContactConnectionManager.send() and are therefore also
 * OTP-encrypted at the message level.
 *
 * File chunks bypass OtpChannel and are sent as raw FCHUNK lines:
 *   FCHUNK:{fileId}:{chunkIdx}/{totalChunks}:{base64_ciphertext}
 */
object FileTransferEngine {

    private const val TAG        = "FileTransferEngine"
    private const val CHUNK_SIZE = 8 * 1024   // 8 KB: balances transfer efficiency with memory usage

    // ── In-progress receive state ──────────────────────────────────────────────

    private data class ReceiveState(
        val fileId:      String,
        val filename:    String,
        val totalBytes:  Long,
        val expectedSha: String,
        val totalChunks: Int,
        val fileKey:     ByteArray,
        val chunkDir:    File,
        val contactId:   Long,
        val received:    MutableSet<Int> = ConcurrentHashMap.newKeySet()
    )

    private val receives = ConcurrentHashMap<String, ReceiveState>()

    // ── Send ──────────────────────────────────────────────────────────────────

    /**
     * Send [fileUri] to [contactId].
     *
     * Generates a fresh ChaCha20-Poly1305 key for this file transfer and
     * includes the hex-encoded key in the FILE_START control message.
     * Calls [onProgress] with 0.0–1.0 as chunks are sent.
     * Returns true on success.
     */
    suspend fun sendFile(
        application: Application,
        contactId: Long,
        fileUri: Uri,
        onProgress: (Float) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val filename = resolveFileName(application, fileUri)
            val rawBytes = application.contentResolver.openInputStream(fileUri)
                ?.readBytes() ?: return@withContext false
            if (rawBytes.isEmpty()) return@withContext false

            val fileKey     = FileChaCha20.generateKey()
            val hexKey      = fileKey.joinToString("") { "%02x".format(it) }
            val sha256      = sha256hex(rawBytes)
            val fileId      = UUID.randomUUID().toString().replace("-", "").take(16)
            val totalChunks = (rawBytes.size + CHUNK_SIZE - 1) / CHUNK_SIZE

            // Announce over the OTP-protected control channel
            // Payload: fileId:totalBytes:sha256:totalChunks:hexKey64:filename
            // filename is LAST so colons inside the name are preserved by split(limit=6)
            val announced = ContactConnectionManager.send(
                contactId,
                "INFRA:FILE_START:$fileId:${rawBytes.size}:$sha256:$totalChunks:$hexKey:$filename"
            )
            if (!announced) return@withContext false

            // Send chunks as raw FCHUNK lines (ChaCha20-Poly1305 encrypted, bypasses message OTP)
            for (i in 0 until totalChunks) {
                val start = i * CHUNK_SIZE
                val end   = minOf(start + CHUNK_SIZE, rawBytes.size)
                val chunk = rawBytes.copyOfRange(start, end)

                val cipherBytes = FileChaCha20.encrypt(chunk, fileKey, fileId, i)

                val b64  = Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
                val line = "FCHUNK:$fileId:$i/$totalChunks:$b64"
                ContactConnectionManager.sendRaw(contactId, line)
                onProgress((i + 1).toFloat() / totalChunks)
            }

            ContactConnectionManager.send(contactId, "INFRA:FILE_DONE:$fileId:$sha256")

            // Record as sent in the chat
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            MessageViewModel(application).insertMessage(
                MessageData(
                    timestamp   = ts,
                    messageType = "FILE_SENT",
                    content     = filename,
                    sender      = "me",
                    contactId   = contactId,
                    direction   = MessageData.SENT
                )
            ) { }

            true
        } catch (e: Exception) {
            Log.e(TAG, "sendFile failed: ${e.message}")
            false
        }
    }

    // ── Receive: control messages ──────────────────────────────────────────────

    /**
     * Called when INFRA:FILE_START is received.
     * Payload: {fileId}:{totalBytes}:{sha256}:{totalChunks}:{hexKey64}:{filename}
     */
    fun onFileStart(application: Application, contactId: Long, payload: String) {
        try {
            val parts       = payload.split(":", limit = 6)
            if (parts.size < 6) return
            val fileId      = parts[0]
            val totalBytes  = parts[1].toLong()
            val sha256      = parts[2]
            val totalChunks = parts[3].toInt()
            val hexKey      = parts[4]
            val filename    = parts[5]

            // Decode the 64-character hex key to 32 bytes
            val fileKey = ByteArray(hexKey.length / 2) { i ->
                hexKey.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }

            val chunkDir = File(application.cacheDir, "file_recv/$fileId").also { it.mkdirs() }
            receives[fileId] = ReceiveState(
                fileId      = fileId,
                filename    = filename,
                totalBytes  = totalBytes,
                expectedSha = sha256,
                totalChunks = totalChunks,
                fileKey     = fileKey,
                chunkDir    = chunkDir,
                contactId   = contactId
            )

            ContactConnectionManager.send(contactId, "INFRA:FILE_ACK:$fileId")
            Log.i(TAG, "[$contactId] Receiving '$filename' ($totalBytes B, $totalChunks chunks)")
        } catch (e: Exception) {
            Log.w(TAG, "onFileStart parse error: ${e.message}")
        }
    }

    /**
     * Called when INFRA:FILE_DONE is received.
     * Payload: {fileId}:{sha256}
     * Reassembles chunks, verifies SHA-256, saves file, records a chat message.
     */
    fun onFileDone(
        application: Application,
        contactId: Long,
        contactName: String?,
        payload: String
    ) {
        try {
            val colon  = payload.indexOf(':')
            if (colon < 0) return
            val fileId = payload.substring(0, colon)
            val sha256 = payload.substring(colon + 1)

            val state = receives.remove(fileId) ?: return

            if (state.received.size < state.totalChunks) {
                Log.w(TAG, "[$contactId] FILE_DONE but only ${state.received.size}/${state.totalChunks} chunks received")
                return
            }

            // Reassemble chunks in order
            val outDir  = File(application.filesDir, "received_files").also { it.mkdirs() }
            val outFile = File(outDir, state.filename)
            outFile.outputStream().use { out ->
                for (i in 0 until state.totalChunks) {
                    out.write(File(state.chunkDir, "$i").readBytes())
                }
            }
            state.chunkDir.deleteRecursively()

            // Verify integrity
            val actualSha = sha256hex(outFile.readBytes())
            if (actualSha != sha256) {
                Log.w(TAG, "[$contactId] INTEGRITY CHECK FAILED for ${state.filename} — deleting")
                outFile.delete()
                return
            }

            Log.i(TAG, "[$contactId] '${state.filename}' received and verified (${state.totalBytes} B)")

            // Record as received in the chat
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            MessageViewModel(application).insertMessage(
                MessageData(
                    timestamp   = ts,
                    messageType = "FILE_RECEIVED",
                    content     = state.filename,
                    sender      = contactName ?: "unknown",
                    contactId   = contactId,
                    direction   = MessageData.RECEIVED
                )
            ) { }
        } catch (e: Exception) {
            Log.e(TAG, "onFileDone failed: ${e.message}")
        }
    }

    // ── Receive: raw FCHUNK lines ─────────────────────────────────────────────

    /**
     * Called from the connection reader loop for lines starting with "FCHUNK:".
     * These bypass OtpChannel — the content is ChaCha20-Poly1305 encrypted.
     *
     * Wire format: FCHUNK:{fileId}:{chunkIdx}/{total}:{base64}
     */
    fun handleChunk(context: Context, contactId: Long, rawLine: String) {
        try {
            // Manual split to avoid Base64 padding '=' issues
            val body   = rawLine.removePrefix("FCHUNK:")
            val firstColonPos  = body.indexOf(':')
            val fileId = body.substring(0, firstColonPos)
            val rest1  = body.substring(firstColonPos + 1)
            val secondColonPos = rest1.indexOf(':')
            val idxStr = rest1.substring(0, secondColonPos)          // e.g. "3/128"
            val b64    = rest1.substring(secondColonPos + 1)

            val chunkIdx = idxStr.substringBefore('/').toInt()
            val cipher   = Base64.decode(b64, Base64.NO_WRAP)

            val state = receives[fileId] ?: return
            val plain = FileChaCha20.decrypt(cipher, state.fileKey, fileId, chunkIdx) ?: run {
                Log.w(TAG, "[$contactId] Chunk $chunkIdx decrypt failed")
                return
            }

            File(state.chunkDir, "$chunkIdx").writeBytes(plain)
            state.received.add(chunkIdx)
        } catch (e: Exception) {
            Log.w(TAG, "handleChunk error: ${e.message}")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun sha256hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }

    private fun resolveFileName(context: Context, uri: Uri): String {
        var name = "file_${System.currentTimeMillis()}"
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = cursor.getString(idx)
                }
            }
        }
        return name
    }
}
