// ═════════════════════════════════════════════════════════════════════════════
//  DdnsVpnMonitor — Watch VPN state and trigger DDNS updates
// ═════════════════════════════════════════════════════════════════════════════

import Foundation
import NetworkExtension
import os.log

final class DdnsVpnMonitor {

    static let shared = DdnsVpnMonitor()

    private let logger = Logger(subsystem: "com.freedom", category: "DdnsVpnMonitor")
    private var observer: NSObjectProtocol?

    func startMonitoring() {
        observer = NotificationCenter.default.addObserver(
            forName: .NEVPNStatusDidChange,
            object: nil,
            queue: .main
        ) { [weak self] notification in
            guard let connection = notification.object as? NEVPNConnection else { return }
            self?.handleStatusChange(connection.status)
        }
        logger.info("VPN monitoring started")
    }

    func stopMonitoring() {
        if let observer {
            NotificationCenter.default.removeObserver(observer)
        }
        observer = nil
    }

    private func handleStatusChange(_ status: NEVPNStatus) {
        switch status {
        case .connected:
            logger.info("VPN connected — triggering DDNS update")
            Task { await DdnsVpnController.shared.onVpnConnected() }
        case .disconnected:
            logger.info("VPN disconnected — deregistering DDNS")
            Task { await DdnsVpnController.shared.onVpnDisconnected() }
        default:
            break
        }
    }
}
