/**
 * Handles inbound TCP connections to the Freedom server.
 *
 * Connection type is detected by peeking the first 2 bytes:
 *   - 0xFF 0xFF → Bootstrap handshake (new contact exchange)
 *   - Pending reverse contact → Key delivery (step 7 of exchange)
 *   - Otherwise → Normal encrypted message connection
 *
 * Bootstrap protocol uses binary framing (see FreedomCrypto.MAGIC_BOOTSTRAP).
 * Normal connections use line-based Base64 ciphertext.
 */
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
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

var lastPacketReceivedTime = System.currentTimeMillis()

/** Interval between PING transmissions (ms). */
private const val HEARTBEAT_INTERVAL_MS = 30_000L

class TcpClientHandler(
    private val application: Application,
    private val socket: Socket,
    val messageInterface: IMessageReceived,
    private val senderAddress: String? = null
) : Thread() {

    /** Resolved after a successful bootstrap or OTP identification — null for unknown. */
    private var resolvedContact: ContactData? = null

    override fun run() {
        try {
            val input = socket.getInputStream()

            // ── Connection detection: peek first 2 bytes ────────────────
            val peek = ByteArray(2)
            var read = 0
            while (read < 2) {
                val n = input.read(peek, read, 2 - read)
                if (n < 0) { socket.close(); return }
                read += n
            }

            if (peek[0] == 0xFF.toByte() && peek[1] == 0xFF.toByte()) {
                // Binary bootstrap mode
                handleBootstrap(peek)
            } else if (BootstrapKeyHolder.pendingReverseContact != null) {
                // This is A's key delivery connection (step 7) — B's server receives raw 24KB
                handleKeyDelivery(peek)
            } else {
                // Normal line-based OTP message connection
                handleNormalConnection(peek)
            }
        } catch (e: IOException) {
            Log.d(TAG, "[$senderAddress] Disconnected: ${e.message}")
        } finally {
            resolvedContact?.let { ContactConnectionManager.unregisterInbound(it.id) }
            try { socket.close() } catch (_: Exception) { }
        }
    }

    // ── Bootstrap Mode ──────────────────────────────────────────────────

    private fun handleBootstrap(firstTwoBytes: ByteArray) {
        val bootstrapKey = BootstrapKeyHolder.activeBootstrapKey
        if (bootstrapKey == null) {
            Log.w(TAG, "[$senderAddress] Bootstrap packet but no active bootstrap key")
            socket.close()
            return
        }

        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())

        // Read remaining 2 bytes of magic header (we already have FF FF)
        val magic34 = ByteArray(2)
        input.readFully(magic34)
        if (magic34[0] != 0x42.toByte() || magic34[1] != 0x53.toByte()) {
            Log.w(TAG, "[$senderAddress] Invalid bootstrap magic")
            socket.close()
            return
        }

        val keyBuffer = ByteArray(FreedomCrypto.MESSAGE_KEY_BYTES)
        var keyOffset = 0
        var contactName = ""
        var contactDdns = ""
        var contactPorts = ""

        // Read bootstrap packets
        loop@ while (true) {
            val type = input.readByte()
            when (type) {
                FreedomCrypto.BS_KEY_CHUNK -> {
                    val seq   = input.readShort().toInt() and 0xFFFF
                    val total = input.readShort().toInt() and 0xFFFF
                    val len   = input.readShort().toInt() and 0xFFFF
                    val payload = ByteArray(len)
                    input.readFully(payload)
                    val decrypted = FreedomCrypto.xorCyclic(payload, bootstrapKey)
                    System.arraycopy(decrypted, 0, keyBuffer, keyOffset, decrypted.size)
                    keyOffset += decrypted.size
                    Log.d(TAG, "[$senderAddress] Bootstrap KEY_CHUNK $seq/$total (${decrypted.size} bytes)")
                }
                FreedomCrypto.BS_INFO -> {
                    val len = input.readShort().toInt() and 0xFFFF
                    val payload = ByteArray(len)
                    input.readFully(payload)
                    val decrypted = FreedomCrypto.xorCyclic(payload, bootstrapKey)
                    val json = String(decrypted, Charsets.UTF_8)
                    try {
                        @Suppress("UNCHECKED_CAST")
                        val map = Gson().fromJson(json, Map::class.java) as Map<String, Any>
                        contactName  = map["name"]  as? String ?: ""
                        contactDdns  = map["ddns"]  as? String ?: ""
                        contactPorts = map["ports"] as? String ?: ""
                    } catch (e: Exception) {
                        Log.w(TAG, "[$senderAddress] Bootstrap INFO parse error: ${e.message}")
                    }
                    Log.d(TAG, "[$senderAddress] Bootstrap INFO: name=$contactName")
                }
                FreedomCrypto.BS_KEY_DONE -> {
                    Log.d(TAG, "[$senderAddress] Bootstrap KEY_DONE — total key bytes=$keyOffset")
                    break@loop
                }
                else -> {
                    Log.w(TAG, "[$senderAddress] Unknown bootstrap type: $type")
                    break@loop
                }
            }
        }

        // Validate received key
        if (keyOffset != FreedomCrypto.MESSAGE_KEY_BYTES) {
            Log.w(TAG, "[$senderAddress] Bootstrap key size mismatch: $keyOffset != ${FreedomCrypto.MESSAGE_KEY_BYTES}")
            socket.close()
            return
        }

        // Save contact to DB with recvKey0 = reassembled key
        val recvKeyB64 = Base64.encodeToString(keyBuffer, Base64.NO_WRAP)
        val encRecvKey = PasskeySession.encryptField(recvKeyB64) ?: recvKeyB64

        val contact = runBlocking {
            try {
                val dao = FreedomDatabase.getDataseClient(application).contactDao()
                val existing = if (contactDdns.isNotEmpty()) {
                    dao.findByDdns(contactDdns.split(",").first().trim())
                } else null

                val newContact = (existing ?: ContactData(
                    name = contactName.ifEmpty { senderAddress ?: "Unknown" },
                    ddnsNames = contactDdns,
                    ports = contactPorts
                )).let {
                    it.copy(
                        name = contactName.ifEmpty { it.name },
                        ddnsNames = if (contactDdns.isNotEmpty()) contactDdns else it.ddnsNames,
                        ports = if (contactPorts.isNotEmpty()) contactPorts else it.ports,
                        recvKey0 = encRecvKey,
                        recvKeyCreatedAt0 = System.currentTimeMillis(),
                        activeRecvKeyIdx = 0
                    )
                }
                val id = dao.insert(newContact)
                newContact.copy(id = if (existing != null) existing.id else id)
            } catch (e: Exception) {
                Log.w(TAG, "[$senderAddress] Bootstrap save failed: ${e.message}")
                null
            }
        }

        // Send ACK
        output.write(FreedomCrypto.MAGIC_BOOTSTRAP)
        output.writeByte(FreedomCrypto.BS_ACK.toInt())
        output.flush()

        // Notify UI
        if (contact != null) {
            resolvedContact = contact
            BootstrapKeyHolder.onHandshakeComplete?.invoke(contact)
        }

        socket.close()
    }

    // ── Key Delivery Mode ───────────────────────────────────────────────

    private fun handleKeyDelivery(firstTwoBytes: ByteArray) {
        val pendingContact = BootstrapKeyHolder.pendingReverseContact ?: run {
            socket.close(); return
        }
        val bsKey = BootstrapKeyHolder.scannedBootstrapKey

        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())

        // Read the raw 24KB (Key_A→B XOR Key_B→A) — we already have 2 bytes
        val rawData = ByteArray(FreedomCrypto.MESSAGE_KEY_BYTES)
        rawData[0] = firstTwoBytes[0]
        rawData[1] = firstTwoBytes[1]
        var offset = 2
        while (offset < rawData.size) {
            val n = input.read(rawData, offset, rawData.size - offset)
            if (n < 0) break
            offset += n
        }

        if (offset != FreedomCrypto.MESSAGE_KEY_BYTES) {
            Log.w(TAG, "[$senderAddress] Key delivery size mismatch: $offset")
            socket.close()
            return
        }

        // XOR with Key_B→A (our send key) to recover Key_A→B (our recv key)
        val plainSendKey = PasskeySession.decryptField(pendingContact.activeSendKey)
        if (plainSendKey.isNullOrEmpty()) {
            Log.w(TAG, "[$senderAddress] Cannot decrypt send key for XOR")
            socket.close()
            return
        }
        val sendKeyBytes = Base64.decode(plainSendKey, Base64.NO_WRAP)
        val recvKeyBytes = FreedomCrypto.xorCyclic(rawData, sendKeyBytes)
        val recvKeyB64 = Base64.encodeToString(recvKeyBytes, Base64.NO_WRAP)
        val encRecvKey = PasskeySession.encryptField(recvKeyB64) ?: recvKeyB64

        // Send ACK
        output.write(byteArrayOf(0x41, 0x43, 0x4B)) // "ACK"
        output.flush()

        // Read A's contact details (bootstrap-encrypted)
        try {
            // Read magic header
            val magic = ByteArray(4)
            input.readFully(magic)
            if (magic[0] == 0xFF.toByte() && magic[1] == 0xFF.toByte() &&
                magic[2] == 0x42.toByte() && magic[3] == 0x53.toByte() && bsKey != null) {
                val type = input.readByte()
                if (type == FreedomCrypto.BS_INFO) {
                    val len = input.readShort().toInt() and 0xFFFF
                    val payload = ByteArray(len)
                    input.readFully(payload)
                    val decrypted = FreedomCrypto.xorCyclic(payload, bsKey)
                    val json = String(decrypted, Charsets.UTF_8)
                    @Suppress("UNCHECKED_CAST")
                    val map = Gson().fromJson(json, Map::class.java) as Map<String, Any>
                    val aName = map["name"] as? String ?: ""
                    val aDdns = map["ddns"] as? String ?: ""
                    val aPorts = map["ports"] as? String ?: ""

                    // Update contact with A's details and recv key
                    runBlocking {
                        val dao = FreedomDatabase.getDataseClient(application).contactDao()
                        val updated = pendingContact.copy(
                            name = aName.ifEmpty { pendingContact.name },
                            ddnsNames = if (aDdns.isNotEmpty()) aDdns else pendingContact.ddnsNames,
                            ports = if (aPorts.isNotEmpty()) aPorts else pendingContact.ports,
                            recvKey0 = encRecvKey,
                            recvKeyCreatedAt0 = System.currentTimeMillis(),
                            activeRecvKeyIdx = 0
                        )
                        dao.insert(updated)
                        resolvedContact = updated
                    }

                    // Send final ACK
                    output.write(FreedomCrypto.MAGIC_BOOTSTRAP)
                    output.writeByte(FreedomCrypto.BS_ACK.toInt())
                    output.flush()
                }
            }
        } catch (e: Exception) {
            // Step 9 is best-effort — if it fails, at least we have A's key
            Log.w(TAG, "[$senderAddress] Step 9 details read failed: ${e.message}")
            // Still save recv key without updated name/ddns
            runBlocking {
                try {
                    val dao = FreedomDatabase.getDataseClient(application).contactDao()
                    dao.insert(pendingContact.copy(
                        recvKey0 = encRecvKey,
                        recvKeyCreatedAt0 = System.currentTimeMillis(),
                        activeRecvKeyIdx = 0
                    ))
                } catch (_: Exception) {}
            }
        }

        // Notify UI
        resolvedContact?.let { BootstrapKeyHolder.onHandshakeComplete?.invoke(it) }
        BootstrapKeyHolder.pendingReverseContact = null
        BootstrapKeyHolder.scannedBootstrapKey = null

        socket.close()
    }

    // ── Normal OTP Connection ───────────────────────────────────────────

    private fun handleNormalConnection(firstTwoBytes: ByteArray) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val writer = PrintWriter(socket.getOutputStream(), true, Charsets.UTF_8)

        // Reconstruct the first line from the two peeked bytes + remainder
        val restOfFirstLine = reader.readLine() ?: ""
        val firstLine = String(firstTwoBytes, Charsets.UTF_8) + restOfFirstLine

        // Identify contact by trying to decrypt with each contact's recv key
        val contacts = runBlocking {
            try {
                FreedomDatabase.getDataseClient(application).contactDao().getAllOnce()
            } catch (_: Exception) { emptyList() }
        }

        var otpChannel: OtpChannel? = null
        var decryptedFirst: String? = null

        for (contact in contacts) {
            val plainRecvKey = PasskeySession.decryptField(contact.activeRecvKey) ?: continue
            if (plainRecvKey.isEmpty()) continue
            val plainSendKey = PasskeySession.decryptField(contact.activeSendKey) ?: ""

            val recvKeyBytes = try { Base64.decode(plainRecvKey, Base64.NO_WRAP) } catch (_: Exception) { continue }
            val sendKeyBytes = if (plainSendKey.isNotEmpty()) {
                try { Base64.decode(plainSendKey, Base64.NO_WRAP) } catch (_: Exception) { ByteArray(0) }
            } else ByteArray(0)

            val prefs = application.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val threshold = prefs.getInt("key_rotation_threshold", FreedomCrypto.DEFAULT_ROTATION_THRESHOLD)

            val ch = OtpChannel(contact.id, sendKeyBytes, recvKeyBytes, threshold)
            val attempt = ch.decrypt(firstLine.trim())
            if (attempt != null) {
                resolvedContact = contact
                otpChannel = ch
                decryptedFirst = attempt
                break
            }
        }

        // Register this inbound path
        resolvedContact?.let { contact ->
            if (otpChannel != null) {
                ContactConnectionManager.registerInbound(contact.id, writer, otpChannel)
            }
        }

        // Heartbeat sender thread
        val heartbeatThread = Thread {
            try {
                while (!socket.isClosed && !Thread.currentThread().isInterrupted) {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS)
                    writer.println("PING")
                    if (writer.checkError()) break
                }
            } catch (_: InterruptedException) { }
        }.also { it.isDaemon = true; it.start() }

        // Process first line if successfully decrypted
        if (decryptedFirst != null) {
            processMessage(decryptedFirst, writer, otpChannel)
        }

        // Normal message loop
        while (true) {
            val raw = reader.readLine() ?: break
            if (raw.isBlank()) continue

            lastPacketReceivedTime = System.currentTimeMillis()

            // File chunks bypass OtpChannel
            if (raw.startsWith("FCHUNK:")) {
                resolvedContact?.let { c ->
                    FileTransferEngine.handleChunk(application, c.id, raw)
                }
                continue
            }

            // Attempt OTP decryption
            val decrypted = if (otpChannel != null) {
                otpChannel.decrypt(raw) ?: raw
            } else raw

            processMessage(decrypted, writer, otpChannel)
        }

        heartbeatThread.interrupt()
    }

    private fun processMessage(decrypted: String, writer: PrintWriter, channel: OtpChannel?) {
        val parsed = MessageParser.parse(decrypted, senderAddress) ?: return

        Log.d(TAG, "[$senderAddress] ${parsed.type}")

        when (parsed.type) {
            // Respond to keep-alive ping
            MessageType.PING -> {
                writer.println("PONG")
                resolvedContact?.let { ContactConnectionManager.heartbeat(it.id) }
                return
            }
            // Update last-seen timestamp for connection health
            MessageType.PONG -> {
                resolvedContact?.let { ContactConnectionManager.heartbeat(it.id) }
                return
            }
            // Peer wants to discover mutual contacts
            MessageType.SEARCH_REQUEST -> {
                handleSearchRequest(writer, channel)
                return
            }
            // Peer is delivering a rotated key
            MessageType.KEY_ROTATE_DELIVERY -> {
                handleKeyRotateDelivery(parsed.content)
                return
            }
            // Peer's endpoint has changed — update our records
            MessageType.INFRA_DDNS_UPDATE,
            MessageType.INFRA_PORT_UPDATE -> {
                handleEndpointUpdate(parsed.type, parsed.content)
                sendRaw(writer, channel, "INFRA:ACK")
                return
            }
            // Incoming file transfer announcement
            MessageType.INFRA_FILE_START -> {
                resolvedContact?.let { c ->
                    FileTransferEngine.onFileStart(application, c.id, parsed.content)
                }
                return
            }
            // File transfer complete — reassemble and verify
            MessageType.INFRA_FILE_DONE -> {
                resolvedContact?.let { c ->
                    FileTransferEngine.onFileDone(application, c.id, c.name, parsed.content)
                }
                return
            }
            // File transfer acknowledgement/error — informational only
            MessageType.INFRA_FILE_ACK,
            MessageType.INFRA_FILE_ERROR -> return
            // Contact sharing messages
            MessageType.SHARE_REQUEST -> {
                resolvedContact?.let { c ->
                    // payload = {shareId}:{otherName}:{message}
                    val parts = parsed.content.split(":", limit = 3)
                    if (parts.size >= 2) {
                        val shareId = parts[0]
                        val otherName = parts[1]
                        val msg = if (parts.size >= 3) parts[2] else ""
                        ContactShareEngine.handleShareRequest(application, c.id, shareId, otherName, msg)
                    }
                }
                return
            }
            MessageType.SHARE_APPROVE -> {
                resolvedContact?.let { c ->
                    ContactShareEngine.handleShareApprove(application, c.id, parsed.content)
                }
                return
            }
            MessageType.SHARE_DENY -> {
                resolvedContact?.let { c ->
                    ContactShareEngine.handleShareDeny(application, c.id, parsed.content)
                }
                return
            }
            MessageType.SHARE_CONNECT -> {
                resolvedContact?.let { c ->
                    ContactShareEngine.handleShareConnect(application, c.id, parsed.content)
                }
                return
            }
            MessageType.SHARE_FAIL -> {
                resolvedContact?.let { c ->
                    ContactShareEngine.handleShareFail(application, c.id, parsed.content)
                }
                return
            }
            else -> { /* fall through to DB insert below */ }
        }

        // Persist user-visible message
        val contact  = resolvedContact
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        MessageViewModel(application).insertMessage(
            MessageData(
                timestamp   = timestamp,
                messageType = parsed.type.name,
                content     = parsed.content,
                sender      = contact?.name ?: senderAddress,
                contactId   = contact?.id ?: 0L,
                direction   = MessageData.RECEIVED
            )
        ) { }
    }

    // ── Control message handlers ──────────────────────────────────────────────

    private fun handleSearchRequest(writer: PrintWriter, channel: OtpChannel?) {
        val prefs        = application.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val amSearchable = prefs.getBoolean("my_searchable", false)
        val contacts     = runBlocking {
            try {
                FreedomDatabase.getDataseClient(application).contactDao().getAllOnce()
                    .filter { it.isSearchable }
                    .map { mapOf("name" to it.name, "ref" to refForContact(it.id)) }
            } catch (_: Exception) { emptyList() }
        }
        val payload = Gson().toJson(mapOf("searchable" to amSearchable, "contacts" to contacts))
        sendRaw(writer, channel, "SRCH:RESP:$payload")
    }

    private fun handleKeyRotateDelivery(encodedKey: String) {
        val contact = resolvedContact ?: return
        runBlocking {
            try {
                val dao   = FreedomDatabase.getDataseClient(application).contactDao()
                val fresh = dao.findByDdns(contact.ddnsNames.split(",").first().trim()) ?: return@runBlocking
                val encrypted = PasskeySession.encryptField(encodedKey) ?: encodedKey
                val slot = fresh.firstEmptyRecvSlot
                if (slot < 0) { Log.w(TAG, "[$senderAddress] No empty recv slot for rotated key"); return@runBlocking }
                dao.insert(fresh.withRecvKeyInSlot(slot, encrypted))
                Log.i(TAG, "[$senderAddress] Rotated recv key stored in slot $slot")
            } catch (e: Exception) {
                Log.w(TAG, "handleKeyRotateDelivery failed: ${e.message}")
            }
        }
    }

    private fun handleEndpointUpdate(type: MessageType, json: String) {
        val contact = resolvedContact ?: return
        runBlocking {
            try {
                @Suppress("UNCHECKED_CAST")
                val map = Gson().fromJson(json, Map::class.java) as Map<String, Any>
                val dao = FreedomDatabase.getDataseClient(application).contactDao()
                val fresh = dao.findByDdns(contact.ddnsNames.split(",").first().trim()) ?: return@runBlocking
                when (type) {
                    MessageType.INFRA_DDNS_UPDATE -> {
                        val newDdns = map["ddns"] as? String ?: return@runBlocking
                        val existing = fresh.ddnsNames.split(",").map { it.trim() }
                        if (newDdns !in existing) {
                            dao.insert(fresh.copy(ddnsNames = (existing + newDdns).joinToString(",")))
                        }
                    }
                    MessageType.INFRA_PORT_UPDATE -> {
                        val newPort = (map["port"] as? Number)?.toInt()?.toString() ?: return@runBlocking
                        val existing = fresh.ports.split(",").map { it.trim() }
                        if (newPort !in existing) {
                            dao.insert(fresh.copy(ports = (existing + newPort).joinToString(",")))
                        }
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                Log.w(TAG, "handleEndpointUpdate failed: ${e.message}")
            }
        }
    }

    /** Send [plaintext] through the OTP channel if available, else raw. */
    private fun sendRaw(writer: PrintWriter, channel: OtpChannel?, plaintext: String) {
        val line = channel?.encrypt(plaintext) ?: plaintext
        writer.println(line)
    }

    private fun refForContact(id: Long): String =
        Base64.encodeToString(ByteBuffer.allocate(8).putLong(id).array(), Base64.URL_SAFE or Base64.NO_WRAP)

    // Import needed for key ring operations
    private val ContactData.firstEmptyRecvSlot: Int
        get() = when {
            recvKey0.isEmpty() -> 0
            recvKey1.isEmpty() -> 1
            recvKey2.isEmpty() -> 2
            else -> -1
        }

    private fun ContactData.withRecvKeyInSlot(slot: Int, key: String): ContactData = when (slot) {
        0 -> copy(recvKey0 = key, recvKeyCreatedAt0 = System.currentTimeMillis())
        1 -> copy(recvKey1 = key, recvKeyCreatedAt1 = System.currentTimeMillis())
        2 -> copy(recvKey2 = key, recvKeyCreatedAt2 = System.currentTimeMillis())
        else -> this
    }

    companion object {
        private val TAG = TcpClientHandler::class.java.simpleName
    }
}
