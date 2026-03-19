// ═════════════════════════════════════════════════════════════════════════════
//  DdnsUpdater — HTTP update calls for 10 DDNS providers
// ═════════════════════════════════════════════════════════════════════════════

import Foundation
import os.log

enum DdnsUpdater {

    private static let logger = Logger(subsystem: "com.freedom", category: "DdnsUpdater")

    static let DEREGISTER_IP = "0.0.0.0"

    /// Fetch public IP from multiple fallback services.
    static func fetchPublicIp() async throws -> String {
        let services = [
            "https://api.ipify.org",
            "https://api4.my-ip.io/ip",
            "https://checkip.amazonaws.com",
            "https://icanhazip.com",
            "https://ipecho.net/plain"
        ]
        let ipv4 = try! NSRegularExpression(pattern: #"^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$"#)

        for urlString in services {
            guard let url = URL(string: urlString) else { continue }
            do {
                let (data, _) = try await URLSession.shared.data(from: url)
                let ip = String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                let range = NSRange(ip.startIndex..., in: ip)
                if ipv4.firstMatch(in: ip, range: range) != nil {
                    return ip
                }
            } catch {
                continue
            }
        }
        throw NSError(domain: "DdnsUpdater", code: -1, userInfo: [NSLocalizedDescriptionKey: "Could not determine public IP"])
    }

    /// Update DDNS record for the given config.
    static func update(config: DdnsConfig, ip: String) async throws -> String {
        switch config.serviceType {
        case .noip:       return try await updateNoIp(hostname: config.field1, username: config.field2, password: config.field3, ip: ip)
        case .duckdns:    return try await updateDuckDns(domains: config.field1, token: config.field2, ip: ip)
        case .dynu:       return try await updateDynu(hostname: config.field1, username: config.field2, password: config.field3, ip: ip)
        case .cloudflare: return try await updateCloudflare(apiToken: config.field1, zoneId: config.field2, recordId: config.field3, ip: ip)
        case .namecheap:  return try await updateNamecheap(host: config.field1, domain: config.field2, ddnsPassword: config.field3, ip: ip)
        case .desec:      return try await updateDeSec(hostname: config.field1, email: config.field2, password: config.field3, ip: ip)
        case .dynv6:      return try await updateDynv6(hostname: config.field1, token: config.field2, ip: ip)
        case .ydns:       return try await updateYdns(hostname: config.field1, email: config.field2, password: config.field3, ip: ip)
        case .ipv64:      return try await updateIpv64(hostname: config.field1, apiKey: config.field2, ip: ip)
        case .freedns:    return try await updateFreeDns(token: config.field2, ip: ip)
        }
    }

    // MARK: - Provider-specific updaters

    private static func updateNoIp(hostname: String, username: String, password: String, ip: String) async throws -> String {
        let creds = Data("\(username):\(password)".utf8).base64EncodedString()
        var request = URLRequest(url: URL(string: "https://dynupdate.no-ip.com/nic/update?hostname=\(hostname)&myip=\(ip)")!)
        request.setValue("Basic \(creds)", forHTTPHeaderField: "Authorization")
        request.setValue("freedom.app/1.0 contact@example.com", forHTTPHeaderField: "User-Agent")
        return try await fetchString(request)
    }

    private static func updateDuckDns(domains: String, token: String, ip: String) async throws -> String {
        let request = URLRequest(url: URL(string: "https://www.duckdns.org/update?domains=\(domains)&token=\(token)&ip=\(ip)")!)
        return try await fetchString(request)
    }

    private static func updateDynu(hostname: String, username: String, password: String, ip: String) async throws -> String {
        let creds = Data("\(username):\(password)".utf8).base64EncodedString()
        var request = URLRequest(url: URL(string: "https://api.dynu.com/nic/update?hostname=\(hostname)&myip=\(ip)")!)
        request.setValue("Basic \(creds)", forHTTPHeaderField: "Authorization")
        return try await fetchString(request)
    }

    private static func updateCloudflare(apiToken: String, zoneId: String, recordId: String, ip: String) async throws -> String {
        var request = URLRequest(url: URL(string: "https://api.cloudflare.com/client/v4/zones/\(zoneId)/dns_records/\(recordId)")!)
        request.httpMethod = "PATCH"
        request.setValue("Bearer \(apiToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = #"{"content":"\#(ip)"}"#.data(using: .utf8)
        return try await fetchString(request)
    }

    private static func updateNamecheap(host: String, domain: String, ddnsPassword: String, ip: String) async throws -> String {
        let request = URLRequest(url: URL(string: "https://dynamicdns.park-your-domain.com/update?host=\(host)&domain=\(domain)&password=\(ddnsPassword)&ip=\(ip)")!)
        return try await fetchString(request)
    }

    private static func updateDeSec(hostname: String, email: String, password: String, ip: String) async throws -> String {
        let creds = Data("\(email):\(password)".utf8).base64EncodedString()
        var request = URLRequest(url: URL(string: "https://update.dedyn.io/?hostname=\(hostname)&myip=\(ip)")!)
        request.setValue("Basic \(creds)", forHTTPHeaderField: "Authorization")
        return try await fetchString(request)
    }

    private static func updateDynv6(hostname: String, token: String, ip: String) async throws -> String {
        let request = URLRequest(url: URL(string: "https://dynv6.com/api/update?ipv4=\(ip)&token=\(token)&zone=\(hostname)")!)
        return try await fetchString(request)
    }

    private static func updateYdns(hostname: String, email: String, password: String, ip: String) async throws -> String {
        let creds = Data("\(email):\(password)".utf8).base64EncodedString()
        var request = URLRequest(url: URL(string: "https://ydns.io/api/v1/update/?host=\(hostname)&ip=\(ip)")!)
        request.setValue("Basic \(creds)", forHTTPHeaderField: "Authorization")
        return try await fetchString(request)
    }

    private static func updateIpv64(hostname: String, apiKey: String, ip: String) async throws -> String {
        let request = URLRequest(url: URL(string: "https://ipv64.net/nic/update?key=\(apiKey)&domain=\(hostname)")!)
        return try await fetchString(request)
    }

    private static func updateFreeDns(token: String, ip: String) async throws -> String {
        let request = URLRequest(url: URL(string: "https://sync.afraid.org/u/\(token)/?address=\(ip)")!)
        return try await fetchString(request)
    }

    private static func fetchString(_ request: URLRequest) async throws -> String {
        let (data, _) = try await URLSession.shared.data(for: request)
        return String(data: data, encoding: .utf8) ?? "No response"
    }
}
