// ═════════════════════════════════════════════════════════════════════════════
//  SettingsViewModel — Server controls, DDNS, key management
// ═════════════════════════════════════════════════════════════════════════════

import Foundation
import Combine

@MainActor
final class SettingsViewModel: ObservableObject {

    @Published var serverPort: String = "22176"
    @Published var isServerRunning = false
    @Published var myName: String = ""
    @Published var myDomains: String = ""
    @Published var myPorts: String = ""
    @Published var isSearchable = false
    @Published var ddnsConfigs: [DdnsConfig] = []
    @Published var keySegments: [String] = Array(repeating: "", count: 6)
    @Published var statusMessage: String = ""

    init() {
        let defaults = UserDefaults.standard
        serverPort = String(defaults.integer(forKey: "server_port").nonZeroOr(22176))
        myName = defaults.string(forKey: "my_name") ?? ""
        myDomains = defaults.string(forKey: "my_domains") ?? ""
        myPorts = defaults.string(forKey: "my_ports") ?? ""
        isSearchable = defaults.bool(forKey: "my_searchable")
        ddnsConfigs = DdnsConfigStorage.load()
        isServerRunning = TcpServer.shared.isRunning
    }

    func startServer() {
        let port = UInt16(serverPort) ?? 22176
        UserDefaults.standard.set(Int(port), forKey: "server_port")
        TcpServer.shared.port = port
        TcpServer.shared.start()
        UdpServer.shared.port = port
        UdpServer.shared.start()
        isServerRunning = true
        statusMessage = "Server started on port \(port)"
    }

    func stopServer() {
        TcpServer.shared.stop()
        UdpServer.shared.stop()
        isServerRunning = false
        statusMessage = "Server stopped"
    }

    func saveMyInfo() {
        let defaults = UserDefaults.standard
        defaults.set(myName, forKey: "my_name")
        defaults.set(myDomains, forKey: "my_domains")
        defaults.set(myPorts, forKey: "my_ports")
        defaults.set(isSearchable, forKey: "my_searchable")
    }

    func generateKeys() async {
        statusMessage = "Generating keys with entropy collection..."
        let entropy = await FreedomCrypto.collectMotionEntropy(durationMs: 3000)
        let masterKey = await FreedomCrypto.generateKey(
            padLengthBytes: FreedomCrypto.MASTER_PAD_BYTES,
            extraEntropy: entropy
        )
        keySegments = FreedomCrypto.splitKey(masterKey)
        statusMessage = "Keys generated (\(keySegments.count) segments)"
    }

    func addDdnsConfig(_ config: DdnsConfig) {
        ddnsConfigs.append(config)
        DdnsConfigStorage.save(ddnsConfigs)
    }

    func removeDdnsConfig(at index: Int) {
        ddnsConfigs.remove(at: index)
        DdnsConfigStorage.save(ddnsConfigs)
    }

    func updateAllDdns() async {
        do {
            let ip = try await DdnsUpdater.fetchPublicIp()
            for config in ddnsConfigs {
                let result = try await DdnsUpdater.update(config: config, ip: ip)
                statusMessage = "\(config.serviceType.displayName): \(result)"
            }
        } catch {
            statusMessage = "DDNS update failed: \(error.localizedDescription)"
        }
    }
}

private extension Int {
    func nonZeroOr(_ fallback: Int) -> Int {
        self == 0 ? fallback : self
    }
}
