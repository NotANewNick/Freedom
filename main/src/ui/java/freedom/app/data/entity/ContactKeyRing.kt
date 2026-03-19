package freedom.app.data.entity

// ═════════════════════════════════════════════════════════════════════════════
//  ContactKeyRing  —  extension helpers for the 3-slot send/recv key rings
// ═════════════════════════════════════════════════════════════════════════════

// ── Send key ring ───────────────────────────────────────────────────────────

/** The key in the currently active send slot (Base64 of raw key bytes). */
val ContactData.activeSendKey: String
    get() = sendKeyAtSlot(activeSendKeyIdx)

/** The creation timestamp for the currently active send slot. */
val ContactData.activeSendKeyCreatedAt: Long
    get() = sendKeyCreatedAtSlot(activeSendKeyIdx)

/** Messages sent with the currently active send key. */
val ContactData.activeSendMsgCount: Int
    get() = sendMsgCountAtSlot(activeSendKeyIdx)

fun ContactData.sendKeyAtSlot(slot: Int): String = when (slot) {
    0 -> sendKey0; 1 -> sendKey1; 2 -> sendKey2; else -> ""
}

fun ContactData.sendKeyCreatedAtSlot(slot: Int): Long = when (slot) {
    0 -> sendKeyCreatedAt0; 1 -> sendKeyCreatedAt1; 2 -> sendKeyCreatedAt2; else -> 0L
}

fun ContactData.sendMsgCountAtSlot(slot: Int): Int = when (slot) {
    0 -> sendMsgCount0; 1 -> sendMsgCount1; 2 -> sendMsgCount2; else -> 0
}

/** Index of the first empty send slot, or -1 if all three are occupied. */
val ContactData.firstEmptySendSlot: Int
    get() = when {
        sendKey0.isEmpty() -> 0
        sendKey1.isEmpty() -> 1
        sendKey2.isEmpty() -> 2
        else -> -1
    }

/** Return a copy with [key] written into send [slot] (and its timestamp updated). */
fun ContactData.withSendKeyInSlot(
    slot: Int,
    key: String,
    createdAt: Long = System.currentTimeMillis()
): ContactData = when (slot) {
    0 -> copy(sendKey0 = key, sendKeyCreatedAt0 = createdAt, sendMsgCount0 = 0)
    1 -> copy(sendKey1 = key, sendKeyCreatedAt1 = createdAt, sendMsgCount1 = 0)
    2 -> copy(sendKey2 = key, sendKeyCreatedAt2 = createdAt, sendMsgCount2 = 0)
    else -> this
}

/** Return a copy with the message count incremented for the active send key. */
fun ContactData.withIncrementedSendMsgCount(): ContactData = when (activeSendKeyIdx) {
    0 -> copy(sendMsgCount0 = sendMsgCount0 + 1)
    1 -> copy(sendMsgCount1 = sendMsgCount1 + 1)
    2 -> copy(sendMsgCount2 = sendMsgCount2 + 1)
    else -> this
}

/** Return a copy with the message count reset to 0 for the given send slot. */
fun ContactData.withSendMsgCountReset(slot: Int): ContactData = when (slot) {
    0 -> copy(sendMsgCount0 = 0)
    1 -> copy(sendMsgCount1 = 0)
    2 -> copy(sendMsgCount2 = 0)
    else -> this
}

// ── Recv key ring ───────────────────────────────────────────────────────────

/** The key in the currently active recv slot (Base64 of raw key bytes). */
val ContactData.activeRecvKey: String
    get() = recvKeyAtSlot(activeRecvKeyIdx)

/** The creation timestamp for the currently active recv slot. */
val ContactData.activeRecvKeyCreatedAt: Long
    get() = recvKeyCreatedAtSlot(activeRecvKeyIdx)

fun ContactData.recvKeyAtSlot(slot: Int): String = when (slot) {
    0 -> recvKey0; 1 -> recvKey1; 2 -> recvKey2; else -> ""
}

fun ContactData.recvKeyCreatedAtSlot(slot: Int): Long = when (slot) {
    0 -> recvKeyCreatedAt0; 1 -> recvKeyCreatedAt1; 2 -> recvKeyCreatedAt2; else -> 0L
}

/** Index of the first empty recv slot, or -1 if all three are occupied. */
val ContactData.firstEmptyRecvSlot: Int
    get() = when {
        recvKey0.isEmpty() -> 0
        recvKey1.isEmpty() -> 1
        recvKey2.isEmpty() -> 2
        else -> -1
    }

/** Return a copy with [key] written into recv [slot] (and its timestamp updated). */
fun ContactData.withRecvKeyInSlot(
    slot: Int,
    key: String,
    createdAt: Long = System.currentTimeMillis()
): ContactData = when (slot) {
    0 -> copy(recvKey0 = key, recvKeyCreatedAt0 = createdAt)
    1 -> copy(recvKey1 = key, recvKeyCreatedAt1 = createdAt)
    2 -> copy(recvKey2 = key, recvKeyCreatedAt2 = createdAt)
    else -> this
}
