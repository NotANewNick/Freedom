// ═════════════════════════════════════════════════════════════════════════════
//  ContactConnectionManager — Central registry of live socket connections
// ═════════════════════════════════════════════════════════════════════════════

import Foundation
import Network
import Combine

enum ConnectionState: String {
    case connected
    case degraded
    case offline
}

final class ContactConnectionManager: ObservableObject {

    static let shared = ContactConnectionManager()

    private struct Entry {
        let connection: NWConnection
        let channel: OtpChannel
        var lastHeartbeat: Date
    }

    private var inbound:  [Int64: Entry] = [:]
    private var outbound: [Int64: Entry] = [:]
    private let lock = NSLock()

    private static let STALE_TIMEOUT: TimeInterval = 45

    @Published var connectionStates: [Int64: ConnectionState] = [:]

    // MARK: - Register / Unregister

    func registerInbound(contactId: Int64, connection: NWConnection, channel: OtpChannel) {
        lock.lock()
        inbound[contactId] = Entry(connection: connection, channel: channel, lastHeartbeat: Date())
        updateState(contactId)
        lock.unlock()
    }

    func unregisterInbound(contactId: Int64) {
        lock.lock()
        inbound.removeValue(forKey: contactId)
        updateState(contactId)
        lock.unlock()
    }

    func registerOutbound(contactId: Int64, connection: NWConnection, channel: OtpChannel) {
        lock.lock()
        outbound[contactId] = Entry(connection: connection, channel: channel, lastHeartbeat: Date())
        updateState(contactId)
        lock.unlock()
    }

    func unregisterOutbound(contactId: Int64) {
        lock.lock()
        outbound.removeValue(forKey: contactId)
        updateState(contactId)
        lock.unlock()
    }

    // MARK: - Heartbeat

    func heartbeat(contactId: Int64) {
        lock.lock()
        if inbound[contactId] != nil { inbound[contactId]?.lastHeartbeat = Date() }
        if outbound[contactId] != nil { outbound[contactId]?.lastHeartbeat = Date() }
        updateState(contactId)
        lock.unlock()
    }

    // MARK: - Send

    /// Send a message through the OTP channel. Returns true on success.
    func send(contactId: Int64, plaintext: String) -> Bool {
        lock.lock()
        let entry = outbound[contactId] ?? inbound[contactId]
        lock.unlock()

        guard let entry else { return false }
        let encrypted = entry.channel.encrypt(plaintext) ?? plaintext
        let line = encrypted + "\r\n"
        entry.connection.send(content: line.data(using: .utf8), completion: .contentProcessed { _ in })
        return true
    }

    /// Send a raw line (pre-encrypted, e.g., FCHUNK).
    func sendRaw(contactId: Int64, line: String) {
        lock.lock()
        let entry = outbound[contactId] ?? inbound[contactId]
        lock.unlock()

        guard let entry else { return }
        let data = (line + "\r\n").data(using: .utf8)
        entry.connection.send(content: data, completion: .contentProcessed { _ in })
    }

    // MARK: - State

    func state(for contactId: Int64) -> ConnectionState {
        lock.lock()
        defer { lock.unlock() }

        if let inEntry = inbound[contactId] {
            if Date().timeIntervalSince(inEntry.lastHeartbeat) < Self.STALE_TIMEOUT { return .connected }
            return .degraded
        }
        if let outEntry = outbound[contactId] {
            if Date().timeIntervalSince(outEntry.lastHeartbeat) < Self.STALE_TIMEOUT { return .connected }
            return .degraded
        }
        return .offline
    }

    private func updateState(_ contactId: Int64) {
        let newState = stateUnlocked(contactId)
        DispatchQueue.main.async { [weak self] in
            self?.connectionStates[contactId] = newState
        }
    }

    private func stateUnlocked(_ contactId: Int64) -> ConnectionState {
        if let inEntry = inbound[contactId] {
            if Date().timeIntervalSince(inEntry.lastHeartbeat) < Self.STALE_TIMEOUT { return .connected }
            return .degraded
        }
        if let outEntry = outbound[contactId] {
            if Date().timeIntervalSince(outEntry.lastHeartbeat) < Self.STALE_TIMEOUT { return .connected }
            return .degraded
        }
        return .offline
    }
}
