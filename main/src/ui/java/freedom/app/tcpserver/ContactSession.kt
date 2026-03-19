package freedom.app.tcpserver

import java.io.PrintWriter

/**
 * Live connection state for one contact.
 *
 * A contact can have up to two simultaneous connections:
 *   inbound  — their client connected to our server  (TcpClientHandler)
 *   outbound — our client connected to their server  (ConnectionEngine / PersistentOutboundManager)
 *
 * A single [OtpChannel] handles both send and recv encryption (it holds
 * separate send/recv keys internally). Both writers share the same channel.
 */
data class ContactSession(
    val contactId: Long,
    @Volatile var inboundWriter:  PrintWriter? = null,
    @Volatile var outboundWriter: PrintWriter? = null,
    @Volatile var channel: OtpChannel? = null,
    @Volatile var lastHeartbeatMs: Long = System.currentTimeMillis(),
    @Volatile var state: ConnectionState = ConnectionState.OFFLINE
) {
    enum class ConnectionState { CONNECTED, DEGRADED, OFFLINE }

    /** True when at least one socket is alive. */
    val isReachable: Boolean
        get() = inboundWriter != null || outboundWriter != null

    /**
     * Send [plaintext] via the preferred (outbound) writer, falling back to
     * inbound if needed.  The [OtpChannel] encrypts it.
     * Returns true if the line was written to a socket.
     */
    fun send(plaintext: String): Boolean {
        val ch = channel ?: return false

        // Try outbound (client→server) first — preferred path
        val ow = outboundWriter
        if (ow != null) {
            val wire = ch.encrypt(plaintext) ?: return false
            return try { ow.println(wire); !ow.checkError() }
            catch (_: Exception) { false }
        }
        // Fallback: send over the inbound socket
        val iw = inboundWriter
        if (iw != null) {
            val wire = ch.encrypt(plaintext) ?: return false
            return try { iw.println(wire); !iw.checkError() }
            catch (_: Exception) { false }
        }
        return false
    }
}
