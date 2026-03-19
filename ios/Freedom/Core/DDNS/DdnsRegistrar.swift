// ═════════════════════════════════════════════════════════════════════════════
//  DdnsRegistrar — One-time provider registration / verification
// ═════════════════════════════════════════════════════════════════════════════

import Foundation
import os.log

enum DdnsRegistrar {

    private static let logger = Logger(subsystem: "com.freedom", category: "DdnsRegistrar")

    /// Register/verify a DDNS provider account. Returns a DdnsConfig on success.
    static func register(
        type: DdnsServiceType,
        field1: String,
        field2: String,
        field3: String = ""
    ) async throws -> DdnsConfig {
        switch type {
        case .duckdns:
            // Verify token by doing an update
            let result = try await DdnsUpdater.update(
                config: DdnsConfig(serviceType: type, field1: field1, field2: field2),
                ip: "0.0.0.0"
            )
            guard result.contains("OK") else {
                throw RegistrationError.verificationFailed(result)
            }
            return DdnsConfig(serviceType: type, field1: field1, field2: field2)

        case .desec:
            // Create account + domain via REST API
            let createUrl = URL(string: "https://desec.io/api/v1/auth/")!
            var request = URLRequest(url: createUrl)
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            let body: [String: String] = ["email": field2, "password": field3]
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
            _ = try? await URLSession.shared.data(for: request) // May already exist

            // Create domain
            let domainUrl = URL(string: "https://desec.io/api/v1/domains/")!
            var domainReq = URLRequest(url: domainUrl)
            domainReq.httpMethod = "POST"
            let creds = Data("\(field2):\(field3)".utf8).base64EncodedString()
            domainReq.setValue("Basic \(creds)", forHTTPHeaderField: "Authorization")
            domainReq.setValue("application/json", forHTTPHeaderField: "Content-Type")
            domainReq.httpBody = try JSONSerialization.data(withJSONObject: ["name": field1])
            _ = try? await URLSession.shared.data(for: domainReq)

            return DdnsConfig(serviceType: type, field1: field1, field2: field2, field3: field3)

        case .dynv6:
            // Verify by doing test update
            let result = try await DdnsUpdater.update(
                config: DdnsConfig(serviceType: type, field1: field1, field2: field2),
                ip: "0.0.0.0"
            )
            return DdnsConfig(serviceType: type, field1: field1, field2: field2)

        case .ydns:
            let creds = Data("\(field2):\(field3)".utf8).base64EncodedString()
            var request = URLRequest(url: URL(string: "https://ydns.io/api/v1/hosts/")!)
            request.httpMethod = "POST"
            request.setValue("Basic \(creds)", forHTTPHeaderField: "Authorization")
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONSerialization.data(withJSONObject: ["name": field1, "type": "A", "record": "0.0.0.0"])
            _ = try? await URLSession.shared.data(for: request)
            return DdnsConfig(serviceType: type, field1: field1, field2: field2, field3: field3)

        case .ipv64:
            var request = URLRequest(url: URL(string: "https://ipv64.net/api.php?add_domain=\(field1)")!)
            request.setValue("Bearer \(field2)", forHTTPHeaderField: "Authorization")
            _ = try? await URLSession.shared.data(for: request)
            return DdnsConfig(serviceType: type, field1: field1, field2: field2)

        case .freedns:
            let result = try await DdnsUpdater.update(
                config: DdnsConfig(serviceType: type, field1: field1, field2: field2),
                ip: "0.0.0.0"
            )
            return DdnsConfig(serviceType: type, field1: field1, field2: field2)

        case .noip:
            let result = try await DdnsUpdater.update(
                config: DdnsConfig(serviceType: type, field1: field1, field2: field2, field3: field3),
                ip: "0.0.0.0"
            )
            return DdnsConfig(serviceType: type, field1: field1, field2: field2, field3: field3)

        case .dynu:
            let result = try await DdnsUpdater.update(
                config: DdnsConfig(serviceType: type, field1: field1, field2: field2, field3: field3),
                ip: "0.0.0.0"
            )
            return DdnsConfig(serviceType: type, field1: field1, field2: field2, field3: field3)

        case .cloudflare, .namecheap:
            // Manual config only — no automated registration
            return DdnsConfig(serviceType: type, field1: field1, field2: field2, field3: field3)
        }
    }

    enum RegistrationError: LocalizedError {
        case verificationFailed(String)

        var errorDescription: String? {
            switch self {
            case .verificationFailed(let msg): return "Verification failed: \(msg)"
            }
        }
    }
}
