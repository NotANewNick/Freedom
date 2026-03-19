// ═════════════════════════════════════════════════════════════════════════════
//  VPNManager — NEVPNManager wrapper for tunnel management
// ═════════════════════════════════════════════════════════════════════════════

import Foundation
import NetworkExtension
import os.log

final class VPNManager: ObservableObject {

    static let shared = VPNManager()

    private let logger = Logger(subsystem: "com.freedom", category: "VPNManager")
    @Published var status: NEVPNStatus = .disconnected

    private var manager: NETunnelProviderManager?

    init() {
        NotificationCenter.default.addObserver(
            forName: .NEVPNStatusDidChange,
            object: nil,
            queue: .main
        ) { [weak self] notification in
            if let connection = notification.object as? NEVPNConnection {
                self?.status = connection.status
            }
        }
    }

    /// Load or create VPN manager configuration.
    func loadManager() async {
        do {
            let managers = try await NETunnelProviderManager.loadAllFromPreferences()
            if let existing = managers.first {
                manager = existing
            } else {
                let newManager = NETunnelProviderManager()
                newManager.localizedDescription = "Freedom VPN"
                let proto = NETunnelProviderProtocol()
                proto.providerBundleIdentifier = "com.freedom.openvpn-tunnel"
                proto.serverAddress = "Freedom"
                newManager.protocolConfiguration = proto
                newManager.isEnabled = true
                try await newManager.saveToPreferences()
                manager = newManager
            }
            status = manager?.connection.status ?? .disconnected
        } catch {
            logger.error("Failed to load VPN manager: \(error.localizedDescription)")
        }
    }

    /// Start a tunnel using the given profile.
    func startTunnel(profile: TunnelProfile) {
        Task {
            await loadManager()
            guard let manager else { return }

            let proto = manager.protocolConfiguration as? NETunnelProviderProtocol
            proto?.providerConfiguration = [
                "type": profile.type,
                "ovpnPath": profile.ovpnPath,
                "secretKey": profile.secretKey,
                "tunnelId": profile.tunnelId
            ]
            manager.isEnabled = true

            do {
                try await manager.saveToPreferences()
                try manager.connection.startVPNTunnel()
                logger.info("VPN tunnel started: \(profile.name)")
            } catch {
                logger.error("Failed to start VPN: \(error.localizedDescription)")
            }
        }
    }

    /// Stop the active tunnel.
    func stopTunnel() {
        manager?.connection.stopVPNTunnel()
        logger.info("VPN tunnel stopped")
    }
}
