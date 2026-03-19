package freedom.app.tcpserver

import android.util.Log
import freedom.app.tcpserver.ContactSession.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.PrintWriter
import java.util.concurrent.ConcurrentHashMap

/**
 * Central registry of every live socket connection across all contacts.
 */
object ContactConnectionManager {

    private const val TAG = "ContactConnMgr"

    private val sessions = ConcurrentHashMap<Long, ContactSession>()

    /** Emits the current [ConnectionState] for every known contact. */
    private val _states = MutableStateFlow<Map<Long, ConnectionState>>(emptyMap())
    val states: StateFlow<Map<Long, ConnectionState>> = _states

    // ── Registration ──────────────────────────────────────────────────────────

    fun registerInbound(contactId: Long, writer: PrintWriter, channel: OtpChannel) {
        val session = sessions.getOrPut(contactId) { ContactSession(contactId) }
        session.inboundWriter = writer
        session.channel = channel
        updateState(contactId)
        Log.d(TAG, "[$contactId] inbound registered — state=${session.state}")
    }

    fun registerOutbound(contactId: Long, writer: PrintWriter, channel: OtpChannel) {
        val session = sessions.getOrPut(contactId) { ContactSession(contactId) }
        session.outboundWriter = writer
        session.channel = channel
        updateState(contactId)
        Log.d(TAG, "[$contactId] outbound registered — state=${session.state}")
    }

    fun unregisterInbound(contactId: Long) {
        sessions[contactId]?.let {
            it.inboundWriter = null
            updateState(contactId)
            Log.d(TAG, "[$contactId] inbound unregistered — state=${it.state}")
        }
    }

    fun unregisterOutbound(contactId: Long) {
        sessions[contactId]?.let {
            it.outboundWriter = null
            updateState(contactId)
            Log.d(TAG, "[$contactId] outbound unregistered — state=${it.state}")
        }
    }

    // ── Sending ───────────────────────────────────────────────────────────────

    fun send(contactId: Long, plaintext: String): Boolean {
        val session = sessions[contactId]
        if (session == null) {
            Log.w(TAG, "[$contactId] send failed — no session")
            return false
        }
        val ok = session.send(plaintext)
        if (!ok) Log.w(TAG, "[$contactId] send failed — both sockets dead")
        return ok
    }

    /**
     * Write [rawLine] directly to the best available socket without OtpChannel encryption.
     * Used for FCHUNK lines that are already ChaCha20-Poly1305 encrypted by [FileTransferEngine].
     */
    fun sendRaw(contactId: Long, rawLine: String): Boolean {
        val session = sessions[contactId] ?: return false
        val writer  = session.outboundWriter ?: session.inboundWriter ?: return false
        writer.println(rawLine)
        return true
    }

    // ── Heartbeat ─────────────────────────────────────────────────────────────

    fun heartbeat(contactId: Long) {
        sessions[contactId]?.lastHeartbeatMs = System.currentTimeMillis()
    }

    fun checkHeartbeats(staleMs: Long = 45_000L) {
        val now = System.currentTimeMillis()
        sessions.forEach { (id, session) ->
            if (session.isReachable && (now - session.lastHeartbeatMs) > staleMs) {
                if (session.state != ConnectionState.DEGRADED) {
                    session.state = ConnectionState.DEGRADED
                    Log.w(TAG, "[$id] heartbeat stale — marking DEGRADED")
                    publishStates()
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    fun getSession(contactId: Long): ContactSession? = sessions[contactId]

    private fun updateState(contactId: Long) {
        val session = sessions[contactId] ?: return
        val hasOut = session.outboundWriter != null
        val hasIn  = session.inboundWriter  != null
        session.state = when {
            hasOut && hasIn -> ConnectionState.CONNECTED
            hasOut || hasIn -> ConnectionState.DEGRADED
            else            -> ConnectionState.OFFLINE
        }
        publishStates()
    }

    private fun publishStates() {
        _states.value = sessions.mapValues { it.value.state }
    }
}
