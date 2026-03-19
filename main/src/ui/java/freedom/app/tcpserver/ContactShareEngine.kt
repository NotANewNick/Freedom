package freedom.app.tcpserver

import android.app.Application
import android.content.Context
import android.util.Base64
import android.util.Log
import freedom.app.data.entity.ContactData
import freedom.app.data.room.FreedomDatabase
import freedom.app.helper.FreedomCrypto
import freedom.app.security.PasskeySession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Core logic for the Contact Sharing feature.
 *
 * Contact A introduces two of their contacts (B and C) to each other.
 * Both B and C must approve before a bootstrap key is generated and
 * the two are connected via the normal 7-phase bootstrap protocol.
 */
object ContactShareEngine {

    private const val TAG = "ContactShareEngine"

    /** Timeout for pending share states (5 minutes). */
    private const val SHARE_TIMEOUT_MS = 5 * 60 * 1000L

    /** Timeout for the listener waiting for an incoming bootstrap connection (30 seconds). */
    private const val BOOTSTRAP_WAIT_TIMEOUT_MS = 30_000L

    // ── Pending shares initiated by this device (A's perspective) ────────────

    data class ShareState(
        val shareId: String,
        val contact1Id: Long,      // B
        val contact2Id: Long,      // C
        var contact1Approved: Boolean = false,
        var contact2Approved: Boolean = false,
        val message: String = "",
        val createdAt: Long = System.currentTimeMillis()
    )

    private val pendingShares = ConcurrentHashMap<String, ShareState>()

    // ── Pending incoming share requests (B/C's perspective) ──────────────────

    data class IncomingShareRequest(
        val shareId: String,
        val fromContactId: Long,
        val otherContactName: String,
        val message: String,
        val receivedAt: Long = System.currentTimeMillis()
    )

    private val incomingRequests = ConcurrentHashMap<String, IncomingShareRequest>()

    // ── Pending bootstrap keys from share (B's perspective — waiting for bootstrap) ──

    private val expectedBootstraps = ConcurrentHashMap<String, String>()

    // ── Tracking for bootstrap timeout (bootstrapB64 → shareId) ──────────────

    private val bootstrapShareIds = ConcurrentHashMap<String, String>()

    // ── Tracking which contactId sent us the SHARE_CONNECT (bootstrapB64 → contactId of A) ──

    private val bootstrapOriginContact = ConcurrentHashMap<String, Long>()

    // ── Timeout threads for bootstrap waiting (bootstrapB64 → Thread) ─────────

    private val bootstrapTimeoutThreads = ConcurrentHashMap<String, Thread>()

    // ── Rate limiting: contactId → last share request timestamp ──────────────

    private val lastShareTime = ConcurrentHashMap<Long, Long>()

    // ── Callback for UI notifications ────────────────────────────────────────

    @Volatile
    var onShareRequestReceived: ((IncomingShareRequest) -> Unit)? = null

    @Volatile
    var onShareResult: ((shareId: String, success: Boolean, message: String) -> Unit)? = null

    // ═════════════════════════════════════════════════════════════════════════
    //  A's perspective: Initiate a share
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * A initiates sharing contact1 (B) with contact2 (C).
     * Sends SHARE_REQ to both contacts.
     * Returns a pair of (success, message).
     */
    fun initiateShare(
        application: Application,
        contact1Id: Long,
        contact2Id: Long,
        message: String
    ): Pair<Boolean, String> {
        val prefs = application.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val rateLimitSeconds = prefs.getInt("share_rate_limit_seconds", 15)
        val rateLimitMs = rateLimitSeconds * 1000L
        val now = System.currentTimeMillis()

        // Rate limit check for both contacts
        val lastTime1 = lastShareTime[contact1Id] ?: 0L
        val lastTime2 = lastShareTime[contact2Id] ?: 0L
        if (now - lastTime1 < rateLimitMs) {
            val remaining = (rateLimitMs - (now - lastTime1)) / 1000
            return Pair(false, "Rate limited. Wait ${remaining}s before sharing with this contact.")
        }
        if (now - lastTime2 < rateLimitMs) {
            val remaining = (rateLimitMs - (now - lastTime2)) / 1000
            return Pair(false, "Rate limited. Wait ${remaining}s before sharing with this contact.")
        }

        // Look up both contacts
        val dao = FreedomDatabase.getDataseClient(application).contactDao()
        val contact1 = runBlocking { dao.findById(contact1Id) }
        val contact2 = runBlocking { dao.findById(contact2Id) }

        if (contact1 == null || contact2 == null) {
            return Pair(false, "Contact not found in database")
        }

        // Generate share ID (first 16 chars of UUID without dashes)
        val shareId = UUID.randomUUID().toString().replace("-", "").take(16)

        // Store pending state
        pendingShares[shareId] = ShareState(
            shareId = shareId,
            contact1Id = contact1Id,
            contact2Id = contact2Id,
            message = message
        )

        // Update rate limit timestamps
        lastShareTime[contact1Id] = now
        lastShareTime[contact2Id] = now

        // Send SHARE_REQ to B with C's name
        val sent1 = ContactConnectionManager.send(
            contact1Id,
            "INFRA:SHARE_REQ:$shareId:${contact2.name}:$message"
        )

        // Send SHARE_REQ to C with B's name
        val sent2 = ContactConnectionManager.send(
            contact2Id,
            "INFRA:SHARE_REQ:$shareId:${contact1.name}:$message"
        )

        if (!sent1 && !sent2) {
            pendingShares.remove(shareId)
            return Pair(false, "Not connected to either contact")
        }
        if (!sent1) {
            pendingShares.remove(shareId)
            return Pair(false, "Not connected to ${contact1.name}")
        }
        if (!sent2) {
            pendingShares.remove(shareId)
            return Pair(false, "Not connected to ${contact2.name}")
        }

        // Schedule cleanup
        scheduleCleanup(shareId)

        Log.i(TAG, "Share initiated: $shareId (${contact1.name} <-> ${contact2.name})")
        return Pair(true, "Share request sent to ${contact1.name} and ${contact2.name}")
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  B/C's perspective: Receive a share request
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Called when this device receives SHARE_REQ from a contact (A).
     * Stores the request for UI to display.
     */
    fun handleShareRequest(
        application: Application,
        contactId: Long,
        shareId: String,
        otherName: String,
        message: String
    ) {
        val request = IncomingShareRequest(
            shareId = shareId,
            fromContactId = contactId,
            otherContactName = otherName,
            message = message
        )
        incomingRequests[shareId] = request
        Log.i(TAG, "Share request received: $shareId from contact $contactId, other=$otherName")

        // Notify UI
        onShareRequestReceived?.invoke(request)
    }

    /**
     * User approves an incoming share request.
     * Sends SHARE_APPROVE back to A.
     */
    fun approveShare(shareId: String): Boolean {
        val request = incomingRequests.remove(shareId) ?: return false
        val sent = ContactConnectionManager.send(
            request.fromContactId,
            "INFRA:SHARE_APPROVE:$shareId"
        )
        Log.i(TAG, "Share approved: $shareId, sent=$sent")
        return sent
    }

    /**
     * User denies an incoming share request.
     * Sends SHARE_DENY back to A.
     */
    fun denyShare(shareId: String): Boolean {
        val request = incomingRequests.remove(shareId) ?: return false
        val sent = ContactConnectionManager.send(
            request.fromContactId,
            "INFRA:SHARE_DENY:$shareId"
        )
        Log.i(TAG, "Share denied: $shareId, sent=$sent")
        return sent
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  A's perspective: Handle approvals and denials
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Called when A receives SHARE_APPROVE from B or C.
     */
    fun handleShareApprove(application: Application, contactId: Long, shareId: String) {
        val state = pendingShares[shareId] ?: run {
            Log.w(TAG, "SHARE_APPROVE for unknown shareId: $shareId")
            return
        }

        when (contactId) {
            state.contact1Id -> state.contact1Approved = true
            state.contact2Id -> state.contact2Approved = true
            else -> {
                Log.w(TAG, "SHARE_APPROVE from unexpected contact $contactId for $shareId")
                return
            }
        }

        Log.i(TAG, "Share $shareId: contact1Approved=${state.contact1Approved}, contact2Approved=${state.contact2Approved}")

        if (state.contact1Approved && state.contact2Approved) {
            generateAndSendKeys(application, state)
            pendingShares.remove(shareId)
        }
    }

    /**
     * Called when A receives SHARE_DENY from B or C.
     */
    fun handleShareDeny(application: Application, contactId: Long, shareId: String) {
        val state = pendingShares.remove(shareId) ?: run {
            Log.w(TAG, "SHARE_DENY for unknown shareId: $shareId")
            return
        }

        val denierName = runBlocking {
            val dao = FreedomDatabase.getDataseClient(application).contactDao()
            dao.findById(contactId)?.name ?: "Unknown"
        }

        Log.i(TAG, "Share $shareId denied by $denierName (contact $contactId)")
        onShareResult?.invoke(shareId, false, "$denierName declined the introduction")
    }

    /**
     * Called when A receives SHARE_FAIL from B or C.
     * Payload format: {shareId}:{reason}
     *
     * Cleans up the pending share and notifies via the UI callback.
     */
    fun handleShareFail(application: Application, contactId: Long, payload: String) {
        // payload = "{shareId}:{reason}"
        val parts = payload.split(":", limit = 2)
        val shareId = parts[0]
        val reason = if (parts.size >= 2) parts[1] else "unknown"

        val state = pendingShares.remove(shareId)
        if (state == null) {
            Log.w(TAG, "SHARE_FAIL for unknown shareId: $shareId (reason=$reason)")
            return
        }

        val failMessage = when (reason) {
            "timeout" -> "Sharing failed - Timeout"
            else      -> "Sharing failed - $reason"
        }

        Log.i(TAG, "Share $shareId failed: $failMessage (from contact $contactId)")
        onShareResult?.invoke(shareId, false, failMessage)
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  A generates and sends bootstrap keys
    // ═════════════════════════════════════════════════════════════════════════

    private fun generateAndSendKeys(application: Application, state: ShareState) {
        // Generate fresh 256-byte bootstrap key
        val bootstrapKey = FreedomCrypto.generateBootstrapKey()
        val bootstrapB64 = Base64.encodeToString(bootstrapKey, Base64.NO_WRAP)

        val dao = FreedomDatabase.getDataseClient(application).contactDao()

        val contact1 = runBlocking { dao.findById(state.contact1Id) }
        val contact2 = runBlocking { dao.findById(state.contact2Id) }

        if (contact1 == null || contact2 == null) {
            Log.w(TAG, "Cannot complete share ${state.shareId}: contact not found")
            onShareResult?.invoke(state.shareId, false, "Contact not found in database")
            return
        }

        // Contact1 (B) is the LISTENER
        // Send: INFRA:SHARE_CONNECT:{shareId}:{base64_bootstrap_key}:{contact2Name}
        val sent1 = ContactConnectionManager.send(
            state.contact1Id,
            "INFRA:SHARE_CONNECT:${state.shareId}:$bootstrapB64:${contact2.name}"
        )

        // Contact2 (C) is the CONNECTOR
        // Send: INFRA:SHARE_CONNECT:{shareId}:{base64_bootstrap_key}:{contact1Ddns}:{contact1Port}:{contact1Name}
        val contact1Ddns = contact1.ddnsNames.split(",").first().trim()
        val contact1Port = contact1.ports.split(",").first().trim()
        val sent2 = ContactConnectionManager.send(
            state.contact2Id,
            "INFRA:SHARE_CONNECT:${state.shareId}:$bootstrapB64:$contact1Ddns:$contact1Port:${contact1.name}"
        )

        if (sent1 && sent2) {
            Log.i(TAG, "Share ${state.shareId}: bootstrap keys sent to both contacts")
            onShareResult?.invoke(state.shareId, true, "${contact1.name} and ${contact2.name} are connecting")
        } else {
            Log.w(TAG, "Share ${state.shareId}: failed to send bootstrap keys (sent1=$sent1, sent2=$sent2)")
            onShareResult?.invoke(state.shareId, false, "Failed to deliver bootstrap keys")
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  B/C's perspective: Handle SHARE_CONNECT
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Called when this device receives SHARE_CONNECT from A.
     *
     * Payload variants:
     *   3 fields → {shareId}:{base64key}:{otherName}             → LISTENER (B role)
     *   5 fields → {shareId}:{base64key}:{ddns}:{port}:{otherName} → CONNECTOR (C role)
     *
     * Note: the shareId is already stripped by the parser; [payload] starts after the shareId.
     */
    fun handleShareConnect(application: Application, contactId: Long, fullPayload: String) {
        // fullPayload = "{shareId}:{base64key}:{...}"
        // Split into at most 5 parts.
        // Listener (B):  {shareId}:{base64key}:{otherName}
        // Connector (C): {shareId}:{base64key}:{ddns}:{port}:{otherName}
        //
        // Detection: split into 5 parts. If parts[3] is a valid port number AND we have
        // 5 parts, it's the connector variant. Otherwise treat remaining as listener.
        val parts = fullPayload.split(":", limit = 5)

        if (parts.size < 3) {
            Log.w(TAG, "SHARE_CONNECT invalid payload: $fullPayload")
            return
        }

        val shareId = parts[0]
        val bootstrapB64 = parts[1]
        val bootstrapKey = try {
            Base64.decode(bootstrapB64, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "SHARE_CONNECT invalid bootstrap key: ${e.message}")
            return
        }

        // Detect connector variant: 5 parts where parts[3] is a valid port number
        val isConnector = parts.size == 5 && parts[3].toIntOrNull() != null

        if (isConnector) {
            // CONNECTOR role (C): {shareId}:{base64key}:{ddns}:{port}:{otherName}
            val ddns = parts[2]
            val port = parts[3].toInt()
            val otherName = parts[4]
            handleConnectorRole(application, shareId, bootstrapKey, ddns, port, otherName)
        } else {
            // LISTENER role (B): {shareId}:{base64key}:{otherName}
            // otherName is everything after shareId:base64key: — it may contain colons
            val prefixLen = shareId.length + 1 + bootstrapB64.length + 1
            val otherName = if (fullPayload.length > prefixLen) {
                fullPayload.substring(prefixLen)
            } else {
                parts.getOrElse(2) { "" }
            }
            handleListenerRole(application, contactId, shareId, bootstrapKey, otherName)
        }
    }

    /**
     * B (listener) stores the bootstrap key and waits for incoming bootstrap.
     * A 30-second timeout is started; if bootstrap doesn't complete in time,
     * the expected state is cleaned up and SHARE_FAIL is sent back to A.
     */
    private fun handleListenerRole(
        application: Application,
        contactId: Long,
        shareId: String,
        bootstrapKey: ByteArray,
        otherName: String
    ) {
        val bootstrapB64 = Base64.encodeToString(bootstrapKey, Base64.NO_WRAP)
        expectedBootstraps[bootstrapB64] = otherName
        bootstrapShareIds[bootstrapB64] = shareId
        bootstrapOriginContact[bootstrapB64] = contactId

        // Store the bootstrap key in BootstrapKeyHolder so TcpClientHandler accepts it
        BootstrapKeyHolder.activeBootstrapKey = bootstrapKey
        BootstrapKeyHolder.onHandshakeComplete = { newContact ->
            // Bootstrap succeeded — cancel the timeout and clean up tracking maps
            bootstrapTimeoutThreads.remove(bootstrapB64)?.interrupt()
            expectedBootstraps.remove(bootstrapB64)
            bootstrapShareIds.remove(bootstrapB64)
            bootstrapOriginContact.remove(bootstrapB64)
            Log.i(TAG, "Share bootstrap complete (listener): connected to ${newContact.name}")

            // Step 6-like: deliver our key to the new contact
            GlobalScope.launch(Dispatchers.IO) {
                deliverKeyAfterBootstrap(application, newContact, bootstrapKey)
            }
        }

        // Start 30-second timeout for bootstrap completion
        val timeoutThread = Thread {
            try {
                Thread.sleep(BOOTSTRAP_WAIT_TIMEOUT_MS)

                // If we get here, the bootstrap didn't complete in time
                val stillPending = expectedBootstraps.remove(bootstrapB64)
                if (stillPending != null) {
                    bootstrapShareIds.remove(bootstrapB64)
                    bootstrapOriginContact.remove(bootstrapB64)
                    bootstrapTimeoutThreads.remove(bootstrapB64)

                    // Clear BootstrapKeyHolder so stale state doesn't linger
                    if (BootstrapKeyHolder.activeBootstrapKey?.contentEquals(bootstrapKey) == true) {
                        BootstrapKeyHolder.activeBootstrapKey = null
                        BootstrapKeyHolder.onHandshakeComplete = null
                    }

                    Log.w(TAG, "Share $shareId: bootstrap wait timed out after ${BOOTSTRAP_WAIT_TIMEOUT_MS / 1000}s")

                    // Send SHARE_FAIL back to A (the contact who sent SHARE_CONNECT)
                    ContactConnectionManager.send(
                        contactId,
                        "INFRA:SHARE_FAIL:$shareId:timeout"
                    )

                    onShareResult?.invoke(shareId, false, "Sharing failed - Timeout")
                }
            } catch (_: InterruptedException) {
                // Timeout was cancelled because bootstrap completed — nothing to do
            }
        }.apply {
            isDaemon = true
            start()
        }
        bootstrapTimeoutThreads[bootstrapB64] = timeoutThread

        Log.i(TAG, "Share $shareId: listener role ready, waiting for bootstrap from $otherName (${BOOTSTRAP_WAIT_TIMEOUT_MS / 1000}s timeout)")
    }

    /**
     * C (connector) initiates a bootstrap connection to B.
     */
    private fun handleConnectorRole(
        application: Application,
        shareId: String,
        bootstrapKey: ByteArray,
        ddns: String,
        port: Int,
        otherName: String
    ) {
        Log.i(TAG, "Share $shareId: connector role, connecting to $otherName at $ddns:$port")

        GlobalScope.launch(Dispatchers.IO) {
            try {
                // Generate our 24KB message key
                val myKey = FreedomCrypto.generateMessageKey()
                val myKeyB64 = Base64.encodeToString(myKey, Base64.NO_WRAP)
                val encSendKey = PasskeySession.encryptField(myKeyB64) ?: myKeyB64

                val prefs = application.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                val myName = prefs.getString("my_name", "") ?: ""
                val myDdns = prefs.getString("my_domains", "") ?: ""
                val myPorts = prefs.getString("my_ports", "") ?: ""

                val myInfo = mapOf(
                    "name" to myName,
                    "ddns" to myDdns,
                    "ports" to myPorts
                )

                // Create a temporary contact to use ConnectionEngine
                val tempContact = ContactData(
                    name = otherName,
                    ddnsNames = ddns,
                    ports = port.toString()
                )

                val msgInterface = object : IMessageReceived {
                    override fun messageReceived(message: ByteArray, count: Int?) {}
                    override fun messageReceivedInString(message: String) {}
                }

                val engine = ConnectionEngine(application, tempContact, msgInterface)

                // Send our key via bootstrap protocol
                val ok = engine.bootstrapSendKey(ddns, port, bootstrapKey, myKey, myInfo)

                if (ok) {
                    Log.i(TAG, "Share $shareId: bootstrap key sent successfully to $otherName")

                    // Save the contact (without recv key yet — will be delivered by B)
                    val dao = FreedomDatabase.getDataseClient(application).contactDao()
                    val existing = dao.findByDdns(ddns)
                    val contactToSave = (existing ?: tempContact).copy(
                        name = otherName,
                        ddnsNames = if (existing?.ddnsNames?.isNotEmpty() == true) existing.ddnsNames else ddns,
                        ports = if (existing?.ports?.isNotEmpty() == true) existing.ports else port.toString(),
                        sendKey0 = encSendKey,
                        sendKeyCreatedAt0 = System.currentTimeMillis(),
                        activeSendKeyIdx = 0
                    )
                    val insertedId = dao.insert(contactToSave)
                    val savedContact = contactToSave.copy(
                        id = if (existing != null) existing.id else insertedId
                    )

                    // Now set up to receive B's key delivery
                    BootstrapKeyHolder.pendingReverseContact = savedContact
                    BootstrapKeyHolder.scannedBootstrapKey = bootstrapKey

                    Log.i(TAG, "Share $shareId: waiting for $otherName's key delivery")
                } else {
                    Log.w(TAG, "Share $shareId: bootstrap send failed to $ddns:$port")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Share $shareId: connector error: ${e.message}")
            }
        }
    }

    /**
     * After B receives C's bootstrap, B delivers its own key to C.
     * Mirrors the QR flow step 6-7 (A delivers key to B).
     */
    private suspend fun deliverKeyAfterBootstrap(
        application: Application,
        newContact: ContactData,
        bootstrapKey: ByteArray
    ) {
        try {
            val prefs = application.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

            val myKey = FreedomCrypto.generateMessageKey()
            val myKeyB64 = Base64.encodeToString(myKey, Base64.NO_WRAP)
            val encSendKey = PasskeySession.encryptField(myKeyB64) ?: myKeyB64

            // Get the recv key we just got from the new contact
            val theirKeyB64 = PasskeySession.decryptField(newContact.recvKey0) ?: ""
            val theirKey = if (theirKeyB64.isNotEmpty()) {
                try { Base64.decode(theirKeyB64, Base64.NO_WRAP) } catch (_: Exception) { ByteArray(0) }
            } else ByteArray(0)

            val myName = prefs.getString("my_name", "") ?: ""
            val myDdns = prefs.getString("my_domains", "") ?: ""
            val myPorts = prefs.getString("my_ports", "") ?: ""

            val myInfo = mapOf(
                "name" to myName,
                "ddns" to myDdns,
                "ports" to myPorts
            )

            val msgInterface = object : IMessageReceived {
                override fun messageReceived(message: ByteArray, count: Int?) {}
                override fun messageReceivedInString(message: String) {}
            }

            val engine = ConnectionEngine(application, newContact, msgInterface)
            val ok = engine.bootstrapDeliverKey(newContact, myKey, theirKey, bootstrapKey, myInfo)

            if (ok) {
                // Save our send key for this contact
                val dao = FreedomDatabase.getDataseClient(application).contactDao()
                val fresh = dao.findByDdns(newContact.ddnsNames.split(",").first().trim())
                    ?: newContact
                dao.insert(fresh.copy(
                    sendKey0 = encSendKey,
                    sendKeyCreatedAt0 = System.currentTimeMillis(),
                    activeSendKeyIdx = 0
                ))
                Log.i(TAG, "Key delivery to ${newContact.name} complete")
            } else {
                Log.w(TAG, "Key delivery to ${newContact.name} failed")
            }

            BootstrapKeyHolder.clear()
        } catch (e: Exception) {
            Log.e(TAG, "deliverKeyAfterBootstrap error: ${e.message}")
            BootstrapKeyHolder.clear()
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Bootstrap key check
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Check if an incoming bootstrap key was expected from a contact share.
     * Used by TcpClientHandler to accept shared-contact bootstraps.
     */
    fun isExpectedBootstrap(bootstrapKey: ByteArray): Boolean {
        val b64 = Base64.encodeToString(bootstrapKey, Base64.NO_WRAP)
        return expectedBootstraps.containsKey(b64)
    }

    /**
     * Get the expected contact name for a bootstrap key, or null.
     */
    fun getExpectedBootstrapName(bootstrapKey: ByteArray): String? {
        val b64 = Base64.encodeToString(bootstrapKey, Base64.NO_WRAP)
        return expectedBootstraps[b64]
    }

    /** Get all pending incoming share requests (for UI display). */
    fun getPendingIncomingRequests(): List<IncomingShareRequest> =
        incomingRequests.values.toList()

    // ═════════════════════════════════════════════════════════════════════════
    //  Cleanup
    // ═════════════════════════════════════════════════════════════════════════

    private fun scheduleCleanup(shareId: String) {
        Thread {
            try {
                Thread.sleep(SHARE_TIMEOUT_MS)
                val removed = pendingShares.remove(shareId)
                if (removed != null) {
                    Log.i(TAG, "Share $shareId timed out")
                    onShareResult?.invoke(shareId, false, "Share request timed out")
                }
            } catch (_: InterruptedException) { }
        }.apply {
            isDaemon = true
            start()
        }
    }

    /** Remove stale entries from all maps. Called periodically or on demand. */
    fun cleanupStale() {
        val now = System.currentTimeMillis()
        pendingShares.entries.removeIf { now - it.value.createdAt > SHARE_TIMEOUT_MS }
        incomingRequests.entries.removeIf { now - it.value.receivedAt > SHARE_TIMEOUT_MS }

        // Clean up any orphaned bootstrap tracking entries
        val activeBootstraps = expectedBootstraps.keys.toSet()
        bootstrapShareIds.keys.retainAll(activeBootstraps)
        bootstrapOriginContact.keys.retainAll(activeBootstraps)
        bootstrapTimeoutThreads.keys.removeAll { key ->
            if (key !in activeBootstraps) {
                bootstrapTimeoutThreads[key]?.interrupt()
                true
            } else false
        }
    }
}
