// ═════════════════════════════════════════════════════════════════════════════
//  MessageData — Chat message entity mirroring Android Room schema
// ═════════════════════════════════════════════════════════════════════════════

import Foundation
import GRDB

struct MessageData: Codable, FetchableRecord, MutablePersistableRecord, Identifiable, Equatable {
    static let databaseTableName = "message_data"

    static let SENT     = "SENT"
    static let RECEIVED = "RECEIVED"

    var id: Int64?
    var timestamp: String?
    var messageType: String?
    var content: String?
    var sender: String?
    var contactId: Int64 = 0
    var direction: String = MessageData.RECEIVED

    enum CodingKeys: String, CodingKey {
        case id
        case timestamp
        case messageType = "message_type"
        case content
        case sender
        case contactId = "contact_id"
        case direction
    }

    mutating func didInsert(_ inserted: InsertionSuccess) {
        id = inserted.rowID
    }
}
