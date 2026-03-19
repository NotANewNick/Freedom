// ═════════════════════════════════════════════════════════════════════════════
//  PlayitApiService — playit.gg REST API client
// ═════════════════════════════════════════════════════════════════════════════

import Foundation
import os.log

enum PlayitApiService {

    private static let logger = Logger(subsystem: "com.freedom", category: "PlayitApi")
    private static let baseURL = "https://api.playit.gg"

    /// Claim a new device with playit.gg.
    static func claimDevice(claimCode: String) async throws -> DeviceClaim {
        var request = URLRequest(url: URL(string: "\(baseURL)/claim/exchange")!)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: ["code": claimCode])

        let (data, _) = try await URLSession.shared.data(for: request)
        return try JSONDecoder().decode(DeviceClaim.self, from: data)
    }

    /// Create a TCP tunnel for the device.
    static func createTunnel(secretKey: String, localPort: Int) async throws -> TunnelInfo {
        var request = URLRequest(url: URL(string: "\(baseURL)/tunnels")!)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(secretKey, forHTTPHeaderField: "Authorization")
        request.httpBody = try JSONSerialization.data(withJSONObject: [
            "tunnel_type": "minecraft-tcp",
            "port_type": "tcp",
            "local_ip": "127.0.0.1",
            "local_port": localPort
        ])

        let (data, _) = try await URLSession.shared.data(for: request)
        return try JSONDecoder().decode(TunnelInfo.self, from: data)
    }

    /// Get tunnel status.
    static func getTunnelStatus(secretKey: String, tunnelId: String) async throws -> TunnelInfo {
        var request = URLRequest(url: URL(string: "\(baseURL)/tunnels/\(tunnelId)")!)
        request.setValue(secretKey, forHTTPHeaderField: "Authorization")

        let (data, _) = try await URLSession.shared.data(for: request)
        return try JSONDecoder().decode(TunnelInfo.self, from: data)
    }

    // MARK: - Response types

    struct DeviceClaim: Codable {
        let secretKey: String
        let agentId: String

        enum CodingKeys: String, CodingKey {
            case secretKey = "secret_key"
            case agentId = "agent_id"
        }
    }

    struct TunnelInfo: Codable {
        let id: String
        let publicHost: String?
        let publicPort: Int?
        let status: String?

        enum CodingKeys: String, CodingKey {
            case id
            case publicHost = "connect_address"
            case publicPort = "port"
            case status
        }
    }
}
