package freedom.app.tcpserver

import freedom.app.data.entity.ContactData

/**
 * Ephemeral bootstrap key holder — replaces `my_qr_key` SharedPreferences.
 *
 * Set when the QR code is shown, cleared on success/cancel.
 * The bootstrap key lives only in memory — never persisted to disk.
 */
object BootstrapKeyHolder {

    /** The ephemeral bootstrap key (raw bytes). Set when QR is shown. */
    @Volatile
    var activeBootstrapKey: ByteArray? = null
        @Synchronized get
        @Synchronized set

    /** Callback invoked when the handshake completes successfully. */
    @Volatile
    var onHandshakeComplete: ((ContactData) -> Unit)? = null
        @Synchronized get
        @Synchronized set

    /**
     * State for B's side: after scanning A's QR and sending our key+info,
     * B waits for A's reverse connection to deliver A's key.
     */
    @Volatile
    var pendingReverseContact: ContactData? = null
        @Synchronized get
        @Synchronized set

    /** Bootstrap key from the QR that B scanned (kept for step 9 decryption). */
    @Volatile
    var scannedBootstrapKey: ByteArray? = null
        @Synchronized get
        @Synchronized set

    /** Clear all bootstrap state. */
    @Synchronized
    fun clear() {
        activeBootstrapKey = null
        onHandshakeComplete = null
        pendingReverseContact = null
        scannedBootstrapKey = null
    }
}
