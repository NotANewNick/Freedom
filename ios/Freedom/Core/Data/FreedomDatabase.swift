// ═════════════════════════════════════════════════════════════════════════════
//  FreedomDatabase — GRDB database stack mirroring Android Room v28
// ═════════════════════════════════════════════════════════════════════════════

import Foundation
import GRDB

final class FreedomDatabase {

    static let shared = FreedomDatabase()

    let dbQueue: DatabaseQueue

    private init() {
        let documentsDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        let dbPath = documentsDir.appendingPathComponent("freedom.db").path

        var config = Configuration()
        config.foreignKeysEnabled = true

        do {
            dbQueue = try DatabaseQueue(path: dbPath, configuration: config)
            try migrator.migrate(dbQueue)
        } catch {
            fatalError("Database setup failed: \(error)")
        }
    }

    // MARK: - Schema migrations

    private var migrator: DatabaseMigrator {
        var migrator = DatabaseMigrator()

        migrator.registerMigration("v1_initial") { db in
            // Contacts table
            try db.create(table: "contacts") { t in
                t.autoIncrementedPrimaryKey("id")
                t.column("name", .text).notNull()
                t.column("ddns_names", .text).notNull().defaults(to: "")
                t.column("ports", .text).notNull().defaults(to: "")

                // Send key ring
                t.column("send_key_0", .text).notNull().defaults(to: "")
                t.column("send_key_1", .text).notNull().defaults(to: "")
                t.column("send_key_2", .text).notNull().defaults(to: "")
                t.column("active_send_key_idx", .integer).notNull().defaults(to: 0)
                t.column("send_key_created_at_0", .integer).notNull().defaults(to: 0)
                t.column("send_key_created_at_1", .integer).notNull().defaults(to: 0)
                t.column("send_key_created_at_2", .integer).notNull().defaults(to: 0)
                t.column("send_msg_count_0", .integer).notNull().defaults(to: 0)
                t.column("send_msg_count_1", .integer).notNull().defaults(to: 0)
                t.column("send_msg_count_2", .integer).notNull().defaults(to: 0)

                // Recv key ring
                t.column("recv_key_0", .text).notNull().defaults(to: "")
                t.column("recv_key_1", .text).notNull().defaults(to: "")
                t.column("recv_key_2", .text).notNull().defaults(to: "")
                t.column("active_recv_key_idx", .integer).notNull().defaults(to: 0)
                t.column("recv_key_created_at_0", .integer).notNull().defaults(to: 0)
                t.column("recv_key_created_at_1", .integer).notNull().defaults(to: 0)
                t.column("recv_key_created_at_2", .integer).notNull().defaults(to: 0)

                t.column("added_at", .integer).notNull().defaults(to: 0)
                t.column("preferred_ddns_idx", .integer).notNull().defaults(to: 0)
                t.column("preferred_port_idx", .integer).notNull().defaults(to: 0)
                t.column("preferred_protocol", .text).notNull().defaults(to: "")
                t.column("is_searchable", .boolean).notNull().defaults(to: false)
            }

            // Message data table
            try db.create(table: "message_data") { t in
                t.autoIncrementedPrimaryKey("id")
                t.column("timestamp", .text)
                t.column("message_type", .text)
                t.column("content", .text)
                t.column("sender", .text)
                t.column("contact_id", .integer).notNull().defaults(to: 0)
                t.column("direction", .text).notNull().defaults(to: "RECEIVED")
            }

            // Tunnel profiles table
            try db.create(table: "tunnel_profiles") { t in
                t.autoIncrementedPrimaryKey("id")
                t.column("name", .text).notNull().defaults(to: "")
                t.column("type", .text).notNull().defaults(to: "ovpn")
                t.column("public_host", .text).notNull().defaults(to: "")
                t.column("public_port", .integer).notNull().defaults(to: 0)
                t.column("secret_key", .text).notNull().defaults(to: "")
                t.column("tunnel_id", .text).notNull().defaults(to: "")
                t.column("ovpn_path", .text).notNull().defaults(to: "")
                t.column("priority", .integer).notNull().defaults(to: 0)
                t.column("added_at", .integer).notNull().defaults(to: 0)
            }

            // Config data table
            try db.create(table: "config_data") { t in
                t.autoIncrementedPrimaryKey("id")
                t.column("average_trip_distance", .text).notNull().defaults(to: "")
                t.column("average_trips_month", .text).notNull().defaults(to: "")
                t.column("monthly_business_km", .double).notNull().defaults(to: 0.0)
                t.column("monthly_private_km", .double).notNull().defaults(to: 0.0)
            }
        }

        return migrator
    }

    // MARK: - Contact operations

    func insertContact(_ contact: ContactData) throws -> ContactData {
        try dbQueue.write { db in
            var c = contact
            try c.save(db)
            return c
        }
    }

    func deleteContact(_ contact: ContactData) throws {
        try dbQueue.write { db in
            _ = try contact.delete(db)
        }
    }

    func getAllContacts() throws -> [ContactData] {
        try dbQueue.read { db in
            try ContactData.order(ContactData.Columns.addedAt.desc).fetchAll(db)
        }
    }

    func findContactById(_ id: Int64) throws -> ContactData? {
        try dbQueue.read { db in
            try ContactData.fetchOne(db, key: id)
        }
    }

    func findContactByDdns(_ ddns: String) throws -> ContactData? {
        try dbQueue.read { db in
            try ContactData
                .filter(ContactData.Columns.ddnsNames.like("%\(ddns)%"))
                .fetchOne(db)
        }
    }

    func updatePreferredConnection(contactId: Int64, ddnsIdx: Int, portIdx: Int, protocol proto: String) throws {
        try dbQueue.write { db in
            try db.execute(sql: """
                UPDATE contacts SET preferred_ddns_idx = ?, preferred_port_idx = ?, preferred_protocol = ?
                WHERE id = ?
                """, arguments: [ddnsIdx, portIdx, proto, contactId])
        }
    }

    func updateSearchable(contactId: Int64, searchable: Bool) throws {
        try dbQueue.write { db in
            try db.execute(sql: "UPDATE contacts SET is_searchable = ? WHERE id = ?",
                          arguments: [searchable, contactId])
        }
    }

    // MARK: - Message operations

    func insertMessage(_ message: MessageData) throws -> MessageData {
        try dbQueue.write { db in
            var m = message
            try m.save(db)
            return m
        }
    }

    func getAllMessages() throws -> [MessageData] {
        try dbQueue.read { db in
            try MessageData.order(Column("id").desc).fetchAll(db)
        }
    }

    /// Get messages for a contact, excluding protocol control messages.
    func getMessagesForContact(_ contactId: Int64) throws -> [MessageData] {
        let excludedTypes = [
            "PING", "PONG",
            "INFRA_DDNS_UPDATE", "INFRA_PORT_UPDATE", "INFRA_ENDPOINT_ACK",
            "KEY_ROTATE_FLAG", "KEY_ROTATE_DELIVERY", "KEY_ROTATE_ACK", "KEY_ROTATE_CONFIRM",
            "BOOTSTRAP_KEY_CHUNK", "BOOTSTRAP_KEY_DONE", "BOOTSTRAP_INFO", "BOOTSTRAP_ACK",
            "SEARCH_REQUEST", "SEARCH_RESPONSE",
            "INFRA_FILE_START", "INFRA_FILE_ACK", "INFRA_FILE_DONE", "INFRA_FILE_ERROR",
            "SHARE_REQ", "SHARE_APPROVE", "SHARE_DENY", "SHARE_CONNECT"
        ]
        return try dbQueue.read { db in
            try MessageData
                .filter(Column("contact_id") == contactId)
                .filter(!excludedTypes.contains(Column("message_type")))
                .order(Column("id").asc)
                .fetchAll(db)
        }
    }

    // MARK: - Tunnel profile operations

    func insertTunnelProfile(_ profile: TunnelProfile) throws -> TunnelProfile {
        try dbQueue.write { db in
            var p = profile
            try p.save(db)
            return p
        }
    }

    func deleteTunnelProfile(_ profile: TunnelProfile) throws {
        try dbQueue.write { db in
            _ = try profile.delete(db)
        }
    }

    func getAllTunnelProfiles() throws -> [TunnelProfile] {
        try dbQueue.read { db in
            try TunnelProfile
                .order(Column("priority").asc, Column("added_at").asc)
                .fetchAll(db)
        }
    }

    // MARK: - Observation helpers

    func observeContacts(_ onChange: @escaping ([ContactData]) -> Void) -> DatabaseCancellable {
        let observation = ValueObservation.tracking { db in
            try ContactData.order(ContactData.Columns.addedAt.desc).fetchAll(db)
        }
        return observation.start(in: dbQueue, onError: { _ in }, onChange: onChange)
    }

    func observeMessages(contactId: Int64, _ onChange: @escaping ([MessageData]) -> Void) -> DatabaseCancellable {
        let excludedTypes = [
            "PING", "PONG",
            "INFRA_DDNS_UPDATE", "INFRA_PORT_UPDATE", "INFRA_ENDPOINT_ACK",
            "KEY_ROTATE_FLAG", "KEY_ROTATE_DELIVERY", "KEY_ROTATE_ACK", "KEY_ROTATE_CONFIRM",
            "BOOTSTRAP_KEY_CHUNK", "BOOTSTRAP_KEY_DONE", "BOOTSTRAP_INFO", "BOOTSTRAP_ACK",
            "SEARCH_REQUEST", "SEARCH_RESPONSE",
            "INFRA_FILE_START", "INFRA_FILE_ACK", "INFRA_FILE_DONE", "INFRA_FILE_ERROR",
            "SHARE_REQ", "SHARE_APPROVE", "SHARE_DENY", "SHARE_CONNECT"
        ]
        let observation = ValueObservation.tracking { db in
            try MessageData
                .filter(Column("contact_id") == contactId)
                .filter(!excludedTypes.contains(Column("message_type")))
                .order(Column("id").asc)
                .fetchAll(db)
        }
        return observation.start(in: dbQueue, onError: { _ in }, onChange: onChange)
    }

    func observeTunnelProfiles(_ onChange: @escaping ([TunnelProfile]) -> Void) -> DatabaseCancellable {
        let observation = ValueObservation.tracking { db in
            try TunnelProfile.order(Column("priority").asc, Column("added_at").asc).fetchAll(db)
        }
        return observation.start(in: dbQueue, onError: { _ in }, onChange: onChange)
    }
}
