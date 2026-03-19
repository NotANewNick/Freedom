package freedom.app.tcpserver

import android.app.Application
import android.content.Context
import android.util.Base64
import android.util.Log
import freedom.app.data.entity.ContactData
import freedom.app.data.entity.MessageData
import freedom.app.data.entity.activeSendKey
import freedom.app.data.entity.activeRecvKey
import freedom.app.helper.FreedomCrypto
import freedom.app.parser.MessageParser
import freedom.app.security.PasskeySession
import freedom.app.viewModels.MessageViewModel
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Outgoing TCP connection to a [contact].
 *
 * Connects to the contact's first reachable DDNS:port, sends an initial
 * OTP-encrypted ping to identify ourselves, then enters a message read loop.
 */
class TcpOutgoingHandler(
    private val application: Application,
    private val contact: ContactData,
    private val messageInterface: IMessageReceived,
    private val onResult: (success: Boolean, message: String) -> Unit
) : Thread() {

    override fun run() {
        val plainSendKey = PasskeySession.decryptField(contact.activeSendKey)
            ?: run { onResult(false, "Key decryption failed — re-enter passkey"); return }
        val plainRecvKey = PasskeySession.decryptField(contact.activeRecvKey) ?: ""

        if (plainSendKey.isEmpty()) {
            onResult(false, "Contact has no send key — complete key exchange first")
            return
        }

        val sendKeyBytes = try { Base64.decode(plainSendKey, Base64.NO_WRAP) } catch (_: Exception) {
            onResult(false, "Invalid send key"); return
        }
        val recvKeyBytes = if (plainRecvKey.isNotEmpty()) {
            try { Base64.decode(plainRecvKey, Base64.NO_WRAP) } catch (_: Exception) { ByteArray(0) }
        } else ByteArray(0)

        val ddnsList  = contact.ddnsNames.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val portsList = contact.ports.split(",").mapNotNull { it.trim().toIntOrNull() }

        for (ddns in ddnsList) {
            for (port in portsList) {
                try {
                    Log.d(TAG, "Trying $ddns:$port …")
                    val socket = Socket(ddns, port)
                    handleSocket(socket, sendKeyBytes, recvKeyBytes)
                    return
                } catch (e: Exception) {
                    Log.d(TAG, "Could not connect to $ddns:$port — ${e.message}")
                }
            }
        }
        onResult(false, "All endpoints for ${contact.name} unreachable")
    }

    private fun handleSocket(socket: Socket, sendKeyBytes: ByteArray, recvKeyBytes: ByteArray) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val writer = PrintWriter(socket.getOutputStream(), true, Charsets.UTF_8)
        try {
            val prefs = application.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val threshold = prefs.getInt("key_rotation_threshold", FreedomCrypto.DEFAULT_ROTATION_THRESHOLD)
            val otpChannel = OtpChannel(contact.id, sendKeyBytes, recvKeyBytes, threshold)

            // Send initial encrypted PING to identify ourselves
            val wire = otpChannel.encrypt("PING")
            if (wire == null) {
                onResult(false, "Encryption failed")
                return
            }
            writer.println(wire)

            onResult(true, "Connected to ${contact.name}")

            ContactConnectionManager.registerOutbound(contact.id, writer, otpChannel)

            // ── Message loop ─────────────────────────────────────────────
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue

                lastPacketReceivedTime = System.currentTimeMillis()

                val decrypted = otpChannel.decrypt(line) ?: line
                val parsed = MessageParser.parse(decrypted, contact.ddnsNames.split(",").first()) ?: continue
                Log.d(TAG, "[${contact.name}] ${parsed.type}: ${parsed.content}")

                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                MessageViewModel(application).insertMessage(
                    MessageData(
                        timestamp = timestamp,
                        messageType = parsed.type.name,
                        content = parsed.content,
                        sender = contact.name,
                        contactId = contact.id,
                        direction = MessageData.RECEIVED
                    )
                ) { }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Connection to ${contact.name} closed: ${e.message}")
        } finally {
            ContactConnectionManager.unregisterOutbound(contact.id)
            try { socket.close() } catch (_: Exception) { }
        }
    }

    companion object {
        private val TAG = TcpOutgoingHandler::class.java.simpleName
    }
}
