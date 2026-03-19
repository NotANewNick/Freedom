// ═════════════════════════════════════════════════════════════════════════════
//  DdnsVpnController — VPN state → DDNS update orchestration
// ═════════════════════════════════════════════════════════════════════════════

import Foundation
import os.log

final class DdnsVpnController {

    static let shared = DdnsVpnController()

    private let logger = Logger(subsystem: "com.freedom", category: "DdnsVpnController")

    /// Called when VPN connects. Fetches public IP and updates DDNS if changed.
    func onVpnConnected() async {
        do {
            let newIp = try await DdnsUpdater.fetchPublicIp()
            let lastIp = DdnsConfigStorage.getLastIp()

            guard newIp != lastIp else {
                logger.info("IP unchanged (\(newIp)) — skipping DDNS update")
                return
            }

            logger.info("IP changed: \(lastIp ?? "nil") → \(newIp)")
            DdnsConfigStorage.saveLastIp(newIp)

            let configs = DdnsConfigStorage.load()
            for config in configs {
                do {
                    let result = try await DdnsUpdater.update(config: config, ip: newIp)
                    logger.info("DDNS update [\(config.serviceType.displayName)]: \(result)")
                } catch {
                    logger.error("DDNS update [\(config.serviceType.displayName)] failed: \(error.localizedDescription)")
                }
            }
        } catch {
            logger.error("Failed to fetch public IP: \(error.localizedDescription)")
        }
    }

    /// Called when VPN disconnects. Sets all DDNS to 0.0.0.0.
    func onVpnDisconnected() async {
        DdnsConfigStorage.clearLastIp()

        let configs = DdnsConfigStorage.load()
        for config in configs {
            do {
                let result = try await DdnsUpdater.update(config: config, ip: DdnsUpdater.DEREGISTER_IP)
                logger.info("DDNS deregister [\(config.serviceType.displayName)]: \(result)")
            } catch {
                logger.error("DDNS deregister [\(config.serviceType.displayName)] failed: \(error.localizedDescription)")
            }
        }
    }
}
