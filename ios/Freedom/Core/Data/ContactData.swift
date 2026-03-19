// ═════════════════════════════════════════════════════════════════════════════
//  ContactData — Contact entity mirroring Android Room v28 schema
// ═════════════════════════════════════════════════════════════════════════════

import Foundation
import GRDB

struct ContactData: Codable, FetchableRecord, MutablePersistableRecord, Identifiable, Equatable {
    static let databaseTableName = "contacts"

    var id: Int64?
    var name: String
    var ddnsNames: String        // comma-separated
    var ports: String            // comma-separated

    // ── Send key ring (3 slots) ─────────────────────────────────────────────
    var sendKey0: String = ""
    var sendKey1: String = ""
    var sendKey2: String = ""
    var activeSendKeyIdx: Int = 0
    var sendKeyCreatedAt0: Int64 = 0
    var sendKeyCreatedAt1: Int64 = 0
    var sendKeyCreatedAt2: Int64 = 0
    var sendMsgCount0: Int = 0
    var sendMsgCount1: Int = 0
    var sendMsgCount2: Int = 0

    // ── Recv key ring (3 slots) ─────────────────────────────────────────────
    var recvKey0: String = ""
    var recvKey1: String = ""
    var recvKey2: String = ""
    var activeRecvKeyIdx: Int = 0
    var recvKeyCreatedAt0: Int64 = 0
    var recvKeyCreatedAt1: Int64 = 0
    var recvKeyCreatedAt2: Int64 = 0

    var addedAt: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
    var preferredDdnsIdx: Int = 0
    var preferredPortIdx: Int = 0
    var preferredProtocol: String = ""
    var isSearchable: Bool = false

    // MARK: - Column mapping

    enum Columns: String, ColumnExpression {
        case id
        case name
        case ddnsNames = "ddns_names"
        case ports
        case sendKey0 = "send_key_0"
        case sendKey1 = "send_key_1"
        case sendKey2 = "send_key_2"
        case activeSendKeyIdx = "active_send_key_idx"
        case sendKeyCreatedAt0 = "send_key_created_at_0"
        case sendKeyCreatedAt1 = "send_key_created_at_1"
        case sendKeyCreatedAt2 = "send_key_created_at_2"
        case sendMsgCount0 = "send_msg_count_0"
        case sendMsgCount1 = "send_msg_count_1"
        case sendMsgCount2 = "send_msg_count_2"
        case recvKey0 = "recv_key_0"
        case recvKey1 = "recv_key_1"
        case recvKey2 = "recv_key_2"
        case activeRecvKeyIdx = "active_recv_key_idx"
        case recvKeyCreatedAt0 = "recv_key_created_at_0"
        case recvKeyCreatedAt1 = "recv_key_created_at_1"
        case recvKeyCreatedAt2 = "recv_key_created_at_2"
        case addedAt = "added_at"
        case preferredDdnsIdx = "preferred_ddns_idx"
        case preferredPortIdx = "preferred_port_idx"
        case preferredProtocol = "preferred_protocol"
        case isSearchable = "is_searchable"
    }

    enum CodingKeys: String, CodingKey {
        case id
        case name
        case ddnsNames = "ddns_names"
        case ports
        case sendKey0 = "send_key_0"
        case sendKey1 = "send_key_1"
        case sendKey2 = "send_key_2"
        case activeSendKeyIdx = "active_send_key_idx"
        case sendKeyCreatedAt0 = "send_key_created_at_0"
        case sendKeyCreatedAt1 = "send_key_created_at_1"
        case sendKeyCreatedAt2 = "send_key_created_at_2"
        case sendMsgCount0 = "send_msg_count_0"
        case sendMsgCount1 = "send_msg_count_1"
        case sendMsgCount2 = "send_msg_count_2"
        case recvKey0 = "recv_key_0"
        case recvKey1 = "recv_key_1"
        case recvKey2 = "recv_key_2"
        case activeRecvKeyIdx = "active_recv_key_idx"
        case recvKeyCreatedAt0 = "recv_key_created_at_0"
        case recvKeyCreatedAt1 = "recv_key_created_at_1"
        case recvKeyCreatedAt2 = "recv_key_created_at_2"
        case addedAt = "added_at"
        case preferredDdnsIdx = "preferred_ddns_idx"
        case preferredPortIdx = "preferred_port_idx"
        case preferredProtocol = "preferred_protocol"
        case isSearchable = "is_searchable"
    }

    mutating func didInsert(_ inserted: InsertionSuccess) {
        id = inserted.rowID
    }
}

// MARK: - Key ring accessors

extension ContactData {

    var activeSendKey: String {
        switch activeSendKeyIdx {
        case 0: return sendKey0
        case 1: return sendKey1
        case 2: return sendKey2
        default: return sendKey0
        }
    }

    var activeRecvKey: String {
        switch activeRecvKeyIdx {
        case 0: return recvKey0
        case 1: return recvKey1
        case 2: return recvKey2
        default: return recvKey0
        }
    }

    var activeSendKeyCreatedAt: Int64 {
        switch activeSendKeyIdx {
        case 0: return sendKeyCreatedAt0
        case 1: return sendKeyCreatedAt1
        case 2: return sendKeyCreatedAt2
        default: return sendKeyCreatedAt0
        }
    }

    var activeRecvKeyCreatedAt: Int64 {
        switch activeRecvKeyIdx {
        case 0: return recvKeyCreatedAt0
        case 1: return recvKeyCreatedAt1
        case 2: return recvKeyCreatedAt2
        default: return recvKeyCreatedAt0
        }
    }

    var activeSendMsgCount: Int {
        switch activeSendKeyIdx {
        case 0: return sendMsgCount0
        case 1: return sendMsgCount1
        case 2: return sendMsgCount2
        default: return sendMsgCount0
        }
    }

    func sendKeyAtSlot(_ slot: Int) -> String {
        switch slot {
        case 0: return sendKey0
        case 1: return sendKey1
        case 2: return sendKey2
        default: return ""
        }
    }

    func recvKeyAtSlot(_ slot: Int) -> String {
        switch slot {
        case 0: return recvKey0
        case 1: return recvKey1
        case 2: return recvKey2
        default: return ""
        }
    }

    /// Returns the first empty send key slot index, or -1 if all are occupied.
    var firstEmptySendSlot: Int {
        if sendKey0.isEmpty { return 0 }
        if sendKey1.isEmpty { return 1 }
        if sendKey2.isEmpty { return 2 }
        return -1
    }

    /// Returns the first empty recv key slot index, or -1 if all are occupied.
    var firstEmptyRecvSlot: Int {
        if recvKey0.isEmpty { return 0 }
        if recvKey1.isEmpty { return 1 }
        if recvKey2.isEmpty { return 2 }
        return -1
    }

    /// Returns a copy with the given send key placed in the specified slot.
    func withSendKeyInSlot(_ slot: Int, key: String) -> ContactData {
        var copy = self
        switch slot {
        case 0:
            copy.sendKey0 = key
            copy.sendKeyCreatedAt0 = Int64(Date().timeIntervalSince1970 * 1000)
            copy.sendMsgCount0 = 0
        case 1:
            copy.sendKey1 = key
            copy.sendKeyCreatedAt1 = Int64(Date().timeIntervalSince1970 * 1000)
            copy.sendMsgCount1 = 0
        case 2:
            copy.sendKey2 = key
            copy.sendKeyCreatedAt2 = Int64(Date().timeIntervalSince1970 * 1000)
            copy.sendMsgCount2 = 0
        default: break
        }
        return copy
    }

    /// Returns a copy with the given recv key placed in the specified slot.
    func withRecvKeyInSlot(_ slot: Int, key: String) -> ContactData {
        var copy = self
        switch slot {
        case 0:
            copy.recvKey0 = key
            copy.recvKeyCreatedAt0 = Int64(Date().timeIntervalSince1970 * 1000)
        case 1:
            copy.recvKey1 = key
            copy.recvKeyCreatedAt1 = Int64(Date().timeIntervalSince1970 * 1000)
        case 2:
            copy.recvKey2 = key
            copy.recvKeyCreatedAt2 = Int64(Date().timeIntervalSince1970 * 1000)
        default: break
        }
        return copy
    }

    /// Returns a copy with the send message count incremented for the active slot.
    func withIncrementedSendMsgCount() -> ContactData {
        var copy = self
        switch activeSendKeyIdx {
        case 0: copy.sendMsgCount0 += 1
        case 1: copy.sendMsgCount1 += 1
        case 2: copy.sendMsgCount2 += 1
        default: break
        }
        return copy
    }

    /// Returns a copy with the send message count reset for the given slot.
    func withSendMsgCountReset(slot: Int) -> ContactData {
        var copy = self
        switch slot {
        case 0: copy.sendMsgCount0 = 0
        case 1: copy.sendMsgCount1 = 0
        case 2: copy.sendMsgCount2 = 0
        default: break
        }
        return copy
    }
}
