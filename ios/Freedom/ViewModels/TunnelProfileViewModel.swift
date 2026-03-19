// ═════════════════════════════════════════════════════════════════════════════
//  TunnelProfileViewModel — Observable tunnel profile list
// ═════════════════════════════════════════════════════════════════════════════

import Foundation
import Combine
import GRDB

@MainActor
final class TunnelProfileViewModel: ObservableObject {

    @Published var profiles: [TunnelProfile] = []

    private var cancellable: DatabaseCancellable?
    private let db = FreedomDatabase.shared

    init() {
        cancellable = db.observeTunnelProfiles { [weak self] profiles in
            DispatchQueue.main.async {
                self?.profiles = profiles
            }
        }
    }

    func addProfile(_ profile: TunnelProfile) {
        _ = try? db.insertTunnelProfile(profile)
    }

    func deleteProfile(_ profile: TunnelProfile) {
        try? db.deleteTunnelProfile(profile)
    }
}
