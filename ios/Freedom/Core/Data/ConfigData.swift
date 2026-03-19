// ═════════════════════════════════════════════════════════════════════════════
//  ConfigData — App configuration entity
// ═════════════════════════════════════════════════════════════════════════════

import Foundation
import GRDB

struct ConfigData: Codable, FetchableRecord, MutablePersistableRecord, Identifiable, Equatable {
    static let databaseTableName = "config_data"

    var id: Int64?
    var averageTripDistance: String = ""
    var averageTripsMonth: String = ""
    var monthlyBusinessKm: Double = 0.0
    var monthlyPrivateKm: Double = 0.0

    enum CodingKeys: String, CodingKey {
        case id
        case averageTripDistance = "average_trip_distance"
        case averageTripsMonth = "average_trips_month"
        case monthlyBusinessKm = "monthly_business_km"
        case monthlyPrivateKm = "monthly_private_km"
    }

    mutating func didInsert(_ inserted: InsertionSuccess) {
        id = inserted.rowID
    }
}
