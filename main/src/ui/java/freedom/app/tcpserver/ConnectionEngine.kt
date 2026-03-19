package freedom.app.tcpserver

import android.app.Application
import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import freedom.app.data.entity.ContactData
import freedom.app.data.entity.MessageData
import freedom.app.data.entity.activeSendKey
import freedom.app.data.entity.activeRecvKey
import freedom.app.data.room.FreedomDatabase
import freedom.app.helper.FreedomCrypto
import freedom.app.parser.MessageParser
import freedom.app.parser.MessageType
import freedom.app.security.PasskeySession
import freedom.app.viewModels.MessageViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG                  = "ConnectionEngine"
private const val TCP_TIMEOUT_MS       = 5_000
private const val HEARTBEAT_INTERVAL_MS = 30_000L
private const val HEARTBEAT_GRACE_MS    = 10_000L   // Grace period beyond heartbeat interval before declaring timeout

/**
 * Unified outgoing connection engine with protocol and endpoint fallback.
 */
class ConnectionEngine(
    private val application: Application,
    private val contact: ContactData,
    @Suppress("UNUSED_PARAMETER") messageInterface: IMessageReceived
) {

    private data class Attempt(val ddnsIdx: Int, val portIdx: Int, val protocol: String)

    private fun buildAttempts(ddnsCount: Int, portCount: Int): List<Attempt> {
        val all = mutableListOf<Attempt>()
        for (d in 0 until ddnsCount) {
            for (p in 0 until portCount) {
                all.add(Attempt(d, p, "tcp"))
            }
        }
        if (contact.preferredProtocol.isNotEmpty()) {
            val prefIdx = all.indexOfFirst {
                it.ddnsIdx == contact.preferredDdnsIdx &&
                it.portIdx == contact.preferredPortIdx &&
                it.protocol == contact.preferredProtocol
            }
            if (prefIdx > 0) {
                all.add(0, all.removeAt(prefIdx))
            }
        }
        return all
    }

    /** Attempt to connect to the contact using all DDNS/port/protocol combinations. */
    suspend fun connect(onResult: (success: Boolean, message: String) -> Unit) {
        val plainSendKey = PasskeySession.decryptField(contact.activeSendKey)
            ?: run { onResult(false, "Key decryption failed — re-enter passkey"); return }
        val plainRecvKey = PasskeySession.decryptField(contact.activeRecvKey) ?: ""

        if (plainSendKey.isEmpty()) {
            onResult(false, "Contact has no send key — complete key exchange first"); return
        }

        val sendKeyBytes = try { Base64.decode(plainSendKey, Base64.NO_WRAP) } catch (_: Exception) {
            onResult(false, "Invalid send key"); return
        }
        val recvKeyBytes = if (plainRecvKey.isNotEmpty()) {
            try { Base64.decode(plainRecvKey, Base64.NO_WRAP) } catch (_: Exception) { ByteArray(0) }
        } else ByteArray(0)

        val ddnsList  = contact.ddnsNames.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val portsList = contact.ports.split(",").mapNotNull { it.trim().toIntOrNull() }

        for (attempt in buildAttempts(ddnsList.size, portsList.size)) {
            val ddns = ddnsList[attempt.ddnsIdx]
            val port = portsList[attempt.portIdx]
            Log.d(TAG, "Trying TCP $ddns:$port …")

            val connectionSucceeded = tryTcp(ddns, port, sendKeyBytes, recvKeyBytes, attempt.ddnsIdx, attempt.portIdx)
            if (connectionSucceeded) {
                onResult(true, "Connected to ${contact.name} via TCP ($ddns:$port)")
                return
            }
        }
        onResult(false, "All endpoints for ${contact.name} unreachable")
    }

    // ── TCP ──────────────────────────────────────────────────────────────────

    /** Open a TCP connection, perform OTP handshake, and start the message reader loop. */
    private suspend fun tryTcp(
        ddns: String, port: Int,
        sendKeyBytes: ByteArray, recvKeyBytes: ByteArray,
        ddnsIdx: Int, portIdx: Int
    ): Boolean = withContext(Dispatchers.IO) {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(ddns, port), TCP_TIMEOUT_MS)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer = PrintWriter(socket.getOutputStream(), true, Charsets.UTF_8)

            val prefs = application.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val threshold = prefs.getInt("key_rotation_threshold", FreedomCrypto.DEFAULT_ROTATION_THRESHOLD)

            val otpChannel = OtpChannel(contact.id, sendKeyBytes, recvKeyBytes, threshold)

            // Send an initial encrypted ping to identify ourselves
            val wire = otpChannel.encrypt("PING") ?: run {
                socket.close(); return@withContext false
            }
            writer.println(wire)

            persistPreference(ddnsIdx, portIdx, "tcp")

            ContactConnectionManager.registerOutbound(contact.id, writer, otpChannel)

            // Keep the socket alive; heartbeat + incoming message reader
            Thread {
                try {
                    var lastPing = System.currentTimeMillis()
                    while (true) {
                        socket.soTimeout = (HEARTBEAT_INTERVAL_MS + HEARTBEAT_GRACE_MS).toInt()
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue

                        val now = System.currentTimeMillis()
                        if (now - lastPing >= HEARTBEAT_INTERVAL_MS) {
                            writer.println("PING")
                            lastPing = now
                        }

                        if (line.startsWith("FCHUNK:")) {
                            FileTransferEngine.handleChunk(application, contact.id, line)
                            continue
                        }

                        val decrypted = otpChannel.decrypt(line) ?: line
                        val parsed    = MessageParser.parse(decrypted, ddns) ?: continue

                        when (parsed.type) {
                            MessageType.PING -> { writer.println("PONG"); continue }
                            MessageType.PONG -> { ContactConnectionManager.heartbeat(contact.id); continue }
                            MessageType.KEY_ROTATE_DELIVERY -> {
                                runBlocking { storeIncomingKey(parsed.content) }; continue
                            }
                            MessageType.INFRA_FILE_START -> {
                                FileTransferEngine.onFileStart(application, contact.id, parsed.content)
                                continue
                            }
                            MessageType.INFRA_FILE_DONE -> {
                                FileTransferEngine.onFileDone(application, contact.id, contact.name, parsed.content)
                                continue
                            }
                            MessageType.INFRA_FILE_ACK,
                            MessageType.INFRA_FILE_ERROR -> continue
                            // Contact sharing messages
                            MessageType.SHARE_REQUEST -> {
                                val parts = parsed.content.split(":", limit = 3)
                                if (parts.size >= 2) {
                                    val shareId = parts[0]
                                    val otherName = parts[1]
                                    val msg = if (parts.size >= 3) parts[2] else ""
                                    ContactShareEngine.handleShareRequest(application, contact.id, shareId, otherName, msg)
                                }
                                continue
                            }
                            MessageType.SHARE_APPROVE -> {
                                ContactShareEngine.handleShareApprove(application, contact.id, parsed.content)
                                continue
                            }
                            MessageType.SHARE_DENY -> {
                                ContactShareEngine.handleShareDeny(application, contact.id, parsed.content)
                                continue
                            }
                            MessageType.SHARE_CONNECT -> {
                                ContactShareEngine.handleShareConnect(application, contact.id, parsed.content)
                                continue
                            }
                            MessageType.SHARE_FAIL -> {
                                ContactShareEngine.handleShareFail(application, contact.id, parsed.content)
                                continue
                            }
                            else -> { /* fall through to DB insert */ }
                        }

                        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                        MessageViewModel(application).insertMessage(
                            MessageData(timestamp = ts, messageType = parsed.type.name,
                                        content = parsed.content, sender = contact.name,
                                        contactId = contact.id, direction = MessageData.RECEIVED)
                        ) { }
                    }
                } catch (_: Exception) {
                } finally {
                    ContactConnectionManager.unregisterOutbound(contact.id)
                    try { socket.close() } catch (_: Exception) { }
                }
            }.start()

            true
        } catch (e: Exception) {
            Log.d(TAG, "TCP $ddns:$port failed: ${e.message}")
            try { socket.close() } catch (_: Exception) { }
            false
        }
    }

    // ── Bootstrap: B sends key to A (step 4) ─────────────────────────────────

    /**
     * Connect to A's server at [ddns]:[port] and send our key + contact info.
     * Uses binary bootstrap protocol with [bootstrapKey] for encryption.
     */
    suspend fun bootstrapSendKey(
        ddns: String, port: Int,
        bootstrapKey: ByteArray,
        myKey: ByteArray,
        myInfo: Map<String, String>
    ): Boolean = withContext(Dispatchers.IO) {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(ddns, port), TCP_TIMEOUT_MS)
            val output = DataOutputStream(socket.getOutputStream())
            val input  = DataInputStream(socket.getInputStream())

            // Write magic header
            output.write(FreedomCrypto.MAGIC_BOOTSTRAP)

            // Send key in chunks
            val chunkSize = FreedomCrypto.BOOTSTRAP_KEY_BYTES
            val totalChunks = (myKey.size + chunkSize - 1) / chunkSize
            for (i in 0 until totalChunks) {
                val start = i * chunkSize
                val end   = minOf(start + chunkSize, myKey.size)
                val chunk = myKey.copyOfRange(start, end)
                val encrypted = FreedomCrypto.xorCyclic(chunk, bootstrapKey)

                output.writeByte(FreedomCrypto.BS_KEY_CHUNK.toInt())
                output.writeShort(i)            // seq
                output.writeShort(totalChunks)   // total
                output.writeShort(encrypted.size) // len
                output.write(encrypted)
            }

            // Send contact info
            val infoJson = Gson().toJson(myInfo)
            val infoBytes = infoJson.toByteArray(Charsets.UTF_8)
            val encInfo = FreedomCrypto.xorCyclic(infoBytes, bootstrapKey)
            output.writeByte(FreedomCrypto.BS_INFO.toInt())
            output.writeShort(encInfo.size)
            output.write(encInfo)

            // Send key done
            output.writeByte(FreedomCrypto.BS_KEY_DONE.toInt())
            output.flush()

            // Read ACK
            val ackMagic = ByteArray(4)
            input.readFully(ackMagic)
            val ackType = input.readByte()
            val success = ackType == FreedomCrypto.BS_ACK

            socket.close()
            success
        } catch (e: Exception) {
            Log.w(TAG, "Bootstrap send failed: ${e.message}")
            try { socket.close() } catch (_: Exception) { }
            false
        }
    }

    // ── Bootstrap: A delivers key to B (step 7) ─────────────────────────────

    /**
     * Connect to B's server and deliver Key_A→B XOR Key_B→A.
     * Then send A's contact details bootstrap-encrypted.
     */
    suspend fun bootstrapDeliverKey(
        contact: ContactData,
        myKey: ByteArray,
        theirKey: ByteArray,
        bootstrapKey: ByteArray,
        myInfo: Map<String, String>
    ): Boolean = withContext(Dispatchers.IO) {
        val ddnsList  = contact.ddnsNames.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val portsList = contact.ports.split(",").mapNotNull { it.trim().toIntOrNull() }

        for (ddns in ddnsList) {
            for (port in portsList) {
                val socket = Socket()
                try {
                    socket.connect(InetSocketAddress(ddns, port), TCP_TIMEOUT_MS)
                    val output = DataOutputStream(socket.getOutputStream())
                    val input  = DataInputStream(socket.getInputStream())

                    // Send raw Key_A→B XOR Key_B→A (exactly 24KB, no framing)
                    val xored = FreedomCrypto.xorCyclic(myKey, theirKey)
                    output.write(xored)
                    output.flush()

                    // Wait for ACK
                    val ack = ByteArray(3)
                    input.readFully(ack)

                    // Send A's contact details (bootstrap-encrypted)
                    val infoJson = Gson().toJson(myInfo)
                    val infoBytes = infoJson.toByteArray(Charsets.UTF_8)
                    val encInfo = FreedomCrypto.xorCyclic(infoBytes, bootstrapKey)
                    output.write(FreedomCrypto.MAGIC_BOOTSTRAP)
                    output.writeByte(FreedomCrypto.BS_INFO.toInt())
                    output.writeShort(encInfo.size)
                    output.write(encInfo)
                    output.flush()

                    // Wait for final ACK
                    try {
                        val finalMagic = ByteArray(4)
                        input.readFully(finalMagic)
                        input.readByte() // type
                    } catch (_: Exception) { }

                    socket.close()
                    return@withContext true
                } catch (e: Exception) {
                    Log.d(TAG, "Bootstrap deliver to $ddns:$port failed: ${e.message}")
                    try { socket.close() } catch (_: Exception) { }
                }
            }
        }
        false
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private suspend fun storeIncomingKey(encodedKey: String) {
        runCatching {
            val dao      = FreedomDatabase.getDataseClient(application).contactDao()
            val fresh    = dao.findByDdns(contact.ddnsNames.split(",").first().trim()) ?: return
            val encrypted = PasskeySession.encryptField(encodedKey) ?: encodedKey
            val slot = when {
                fresh.recvKey0.isEmpty() -> 0
                fresh.recvKey1.isEmpty() -> 1
                fresh.recvKey2.isEmpty() -> 2
                else -> -1
            }
            if (slot >= 0) {
                dao.insert(fresh.copy(
                    recvKey0 = if (slot == 0) encrypted else fresh.recvKey0,
                    recvKey1 = if (slot == 1) encrypted else fresh.recvKey1,
                    recvKey2 = if (slot == 2) encrypted else fresh.recvKey2,
                    recvKeyCreatedAt0 = if (slot == 0) System.currentTimeMillis() else fresh.recvKeyCreatedAt0,
                    recvKeyCreatedAt1 = if (slot == 1) System.currentTimeMillis() else fresh.recvKeyCreatedAt1,
                    recvKeyCreatedAt2 = if (slot == 2) System.currentTimeMillis() else fresh.recvKeyCreatedAt2
                ))
            }
        }.onFailure { Log.w(TAG, "storeIncomingKey failed: ${it.message}") }
    }

    private suspend fun persistPreference(ddnsIdx: Int, portIdx: Int, protocol: String) {
        runCatching {
            FreedomDatabase.getDataseClient(application).contactDao()
                .updatePreferredConnection(contact.id, ddnsIdx, portIdx, protocol)
        }.onFailure { Log.w(TAG, "Preference persist failed: ${it.message}") }
    }
}
