// ═════════════════════════════════════════════════════════════════════════════
//  TunnelProfile — VPN tunnel configuration entity
// ═════════════════════════════════════════════════════════════════════════════

import Foundation
import GRDB

struct TunnelProfile: Codable, FetchableRecord, MutablePersistableRecord, Identifiable, Equatable {
    static let databaseTableName = "tunnel_profiles"

    static let TYPE_PLAYIT = "playit"
    static let TYPE_NGROK  = "ngrok"
    static let TYPE_OVPN   = "ovpn"

    var id: Int64?
    var name: String = ""
    var type: String = TYPE_OVPN
    var publicHost: String = ""
    var publicPort: Int = 0
    var secretKey: String = ""
    var tunnelId: String = ""
    var ovpnPath: String = ""
    var priority: Int = 0
    var addedAt: Int64 = Int64(Date().timeIntervalSince1970 * 1000)

    var publicAddress: String {
        if publicHost.isEmpty || publicPort == 0 { return "" }
        return "\(publicHost):\(publicPort)"
    }

    enum CodingKeys: String, CodingKey {
        case id
        case name
        case type
        case publicHost = "public_host"
        case publicPort = "public_port"
        case secretKey = "secret_key"
        case tunnelId = "tunnel_id"
        case ovpnPath = "ovpn_path"
        case priority
        case addedAt = "added_at"
    }

    mutating func didInsert(_ inserted: InsertionSuccess) {
        id = inserted.rowID
    }
}
