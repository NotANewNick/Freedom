// ═════════════════════════════════════════════════════════════════════════════
//  DdnsConfigStorage — Persist DDNS configs via UserDefaults
// ═════════════════════════════════════════════════════════════════════════════

import Foundation

enum DdnsConfigStorage {

    private static let configsKey = "ddns_configs"
    private static let lastIpKey = "ddns_last_ip"

    static func load() -> [DdnsConfig] {
        guard let data = UserDefaults.standard.data(forKey: configsKey) else { return [] }
        return (try? JSONDecoder().decode([DdnsConfig].self, from: data)) ?? []
    }

    static func save(_ configs: [DdnsConfig]) {
        guard let data = try? JSONEncoder().encode(configs) else { return }
        UserDefaults.standard.set(data, forKey: configsKey)
    }

    static func getLastIp() -> String? {
        UserDefaults.standard.string(forKey: lastIpKey)
    }

    static func saveLastIp(_ ip: String) {
        UserDefaults.standard.set(ip, forKey: lastIpKey)
    }

    static func clearLastIp() {
        UserDefaults.standard.removeObject(forKey: lastIpKey)
    }
}
