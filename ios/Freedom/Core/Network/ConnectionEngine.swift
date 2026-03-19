// ═════════════════════════════════════════════════════════════════════════════
//  ConnectionEngine — Unified outgoing connection engine with fallback
// ═════════════════════════════════════════════════════════════════════════════

import Foundation
import Network
import os.log

private let TCP_TIMEOUT: TimeInterval = 5
private let HEARTBEAT_INTERVAL: TimeInterval = 30
private let HEARTBEAT_GRACE: TimeInterval = 10

final class ConnectionEngine {

    private let db = FreedomDatabase.shared
    private let contact: ContactData
    private let logger = Logger(subsystem: "com.freedom", category: "ConnectionEngine")

    init(contact: ContactData) {
        self.contact = contact
    }

    private struct Attempt {
        let ddnsIdx: Int
        let portIdx: Int
        let proto: String
    }

    private func buildAttempts(ddnsCount: Int, portCount: Int) -> [Attempt] {
        var all = [Attempt]()
        for d in 0..<ddnsCount {
            for p in 0..<portCount {
                all.append(Attempt(ddnsIdx: d, portIdx: p, proto: "tcp"))
            }
        }
        // Move preferred to front
        if !contact.preferredProtocol.isEmpty {
            if let prefIdx = all.firstIndex(where: {
                $0.ddnsIdx == contact.preferredDdnsIdx &&
                $0.portIdx == contact.preferredPortIdx &&
                $0.proto == contact.preferredProtocol
            }), prefIdx > 0 {
                let pref = all.remove(at: prefIdx)
                all.insert(pref, at: 0)
            }
        }
        return all
    }

    /// Attempt to connect to the contact using all DDNS/port/protocol combinations.
    func connect() async -> (success: Bool, message: String) {
        guard let plainSendKey = PasskeySession.shared.decryptField(contact.activeSendKey),
              !plainSendKey.isEmpty else {
            return (false, "Key decryption failed — re-enter passkey")
        }
        let plainRecvKey = PasskeySession.shared.decryptField(contact.activeRecvKey) ?? ""

        guard let sendKeyBytes = Data(base64Encoded: plainSendKey) else {
            return (false, "Invalid send key")
        }
        let recvKeyBytes = plainRecvKey.isEmpty ? Data() : (Data(base64Encoded: plainRecvKey) ?? Data())

        let ddnsList = contact.ddnsNames.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        let portsList = contact.ports.split(separator: ",").compactMap { UInt16($0.trimmingCharacters(in: .whitespaces)) }

        for attempt in buildAttempts(ddnsCount: ddnsList.count, portCount: portsList.count) {
            let ddns = ddnsList[attempt.ddnsIdx]
            let port = portsList[attempt.portIdx]
            logger.debug("Trying TCP \(ddns):\(port) …")

            let success = await tryTcp(ddns: ddns, port: port, sendKeyBytes: sendKeyBytes,
                                       recvKeyBytes: recvKeyBytes, ddnsIdx: attempt.ddnsIdx, portIdx: attempt.portIdx)
            if success {
                return (true, "Connected to \(contact.name) via TCP (\(ddns):\(port))")
            }
        }
        return (false, "All endpoints for \(contact.name) unreachable")
    }

    // MARK: - TCP Connection

    private func tryTcp(ddns: String, port: UInt16, sendKeyBytes: Data, recvKeyBytes: Data,
                        ddnsIdx: Int, portIdx: Int) async -> Bool {
        await withCheckedContinuation { continuation in
            let endpoint = NWEndpoint.hostPort(host: .name(ddns, nil), port: NWEndpoint.Port(integerLiteral: port))
            let connection = NWConnection(to: endpoint, using: .tcp)
            let queue = DispatchQueue(label: "com.freedom.conn-engine")
            var resumed = false

            connection.stateUpdateHandler = { [weak self] state in
                guard let self, !resumed else { return }
                switch state {
                case .ready:
                    resumed = true
                    let threshold = UserDefaults.standard.integer(forKey: "key_rotation_threshold")
                    let effectiveThreshold = threshold > 0 ? threshold : FreedomCrypto.DEFAULT_ROTATION_THRESHOLD
                    let otpChannel = OtpChannel(contactId: self.contact.id ?? 0, sendKey: sendKeyBytes,
                                                recvKey: recvKeyBytes, rotationThreshold: effectiveThreshold)

                    // Send initial encrypted ping
                    guard let wire = otpChannel.encrypt("PING") else {
                        connection.cancel()
                        continuation.resume(returning: false)
                        return
                    }
                    let line = wire + "\r\n"
                    connection.send(content: line.data(using: .utf8), completion: .contentProcessed { _ in })

                    try? self.db.updatePreferredConnection(contactId: self.contact.id ?? 0,
                                                           ddnsIdx: ddnsIdx, portIdx: portIdx, protocol: "tcp")

                    ContactConnectionManager.shared.registerOutbound(
                        contactId: self.contact.id ?? 0,
                        connection: connection,
                        channel: otpChannel
                    )

                    // Start reader loop in background
                    self.startReaderLoop(connection: connection, otpChannel: otpChannel, queue: queue)

                    continuation.resume(returning: true)

                case .failed, .cancelled:
                    if !resumed {
                        resumed = true
                        continuation.resume(returning: false)
                    }
                default: break
                }
            }

            connection.start(queue: queue)

            // Timeout
            queue.asyncAfter(deadline: .now() + TCP_TIMEOUT) {
                guard !resumed else { return }
                resumed = true
                connection.cancel()
                continuation.resume(returning: false)
            }
        }
    }

    private func startReaderLoop(connection: NWConnection, otpChannel: OtpChannel, queue: DispatchQueue) {
        var buffer = Data()

        func readMore() {
            connection.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] data, _, isComplete, error in
                guard let self else { return }
                if let data {
                    buffer.append(data)

                    while let newlineIdx = buffer.firstIndex(of: UInt8(ascii: "\n")) {
                        let lineData = buffer.prefix(upTo: newlineIdx)
                        buffer = Data(buffer.suffix(from: buffer.index(after: newlineIdx)))
                        let raw = String(data: Data(lineData), encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                        if raw.isEmpty { continue }

                        if raw.hasPrefix("FCHUNK:") {
                            FileTransferEngine.shared.handleChunk(contactId: self.contact.id ?? 0, rawLine: raw)
                            continue
                        }

                        let decrypted = otpChannel.decrypt(raw) ?? raw
                        guard let parsed = MessageParser.parse(decrypted) else { continue }

                        switch parsed.type {
                        case .PING:
                            let pong = "PONG\r\n"
                            connection.send(content: pong.data(using: .utf8), completion: .contentProcessed { _ in })
                        case .PONG:
                            ContactConnectionManager.shared.heartbeat(contactId: self.contact.id ?? 0)
                        case .KEY_ROTATE_DELIVERY:
                            self.storeIncomingKey(parsed.content)
                        case .INFRA_FILE_START:
                            FileTransferEngine.shared.onFileStart(contactId: self.contact.id ?? 0, payload: parsed.content)
                        case .INFRA_FILE_DONE:
                            FileTransferEngine.shared.onFileDone(contactId: self.contact.id ?? 0,
                                                                 contactName: self.contact.name, payload: parsed.content)
                        case .INFRA_FILE_ACK, .INFRA_FILE_ERROR:
                            break
                        case .SHARE_REQ:
                            self.handleShareReq(parsed.content)
                        case .SHARE_APPROVE:
                            ContactShareEngine.shared.handleShareApprove(fromContactId: self.contact.id ?? 0, shareId: parsed.content)
                        case .SHARE_DENY:
                            ContactShareEngine.shared.handleShareDeny(fromContactId: self.contact.id ?? 0, shareId: parsed.content)
                        case .SHARE_CONNECT:
                            ContactShareEngine.shared.handleShareConnect(fromContactId: self.contact.id ?? 0, payload: parsed.content)
                        case .SHARE_FAIL:
                            ContactShareEngine.shared.handleShareFail(fromContactId: self.contact.id ?? 0, payload: parsed.content)
                        default:
                            let ts = Self.dateFormatter.string(from: Date())
                            let msg = MessageData(timestamp: ts, messageType: parsed.type.rawValue,
                                                  content: parsed.content, sender: self.contact.name,
                                                  contactId: self.contact.id ?? 0, direction: MessageData.RECEIVED)
                            _ = try? self.db.insertMessage(msg)
                        }
                    }
                }

                if isComplete || error != nil {
                    ContactConnectionManager.shared.unregisterOutbound(contactId: self.contact.id ?? 0)
                } else {
                    readMore()
                }
            }
        }

        readMore()
    }

    // MARK: - Bootstrap: B sends key to A (step 4)

    func bootstrapSendKey(ddns: String, port: UInt16, bootstrapKey: Data,
                          myKey: Data, myInfo: [String: String]) async -> Bool {
        await withCheckedContinuation { continuation in
            let endpoint = NWEndpoint.hostPort(host: .name(ddns, nil), port: NWEndpoint.Port(integerLiteral: port))
            let connection = NWConnection(to: endpoint, using: .tcp)
            let queue = DispatchQueue(label: "com.freedom.bootstrap-send")
            var resumed = false

            connection.stateUpdateHandler = { state in
                guard !resumed else { return }
                switch state {
                case .ready:
                    resumed = true
                    self.performBootstrapSend(connection: connection, bootstrapKey: bootstrapKey,
                                             myKey: myKey, myInfo: myInfo) { success in
                        connection.cancel()
                        continuation.resume(returning: success)
                    }
                case .failed, .cancelled:
                    if !resumed { resumed = true; continuation.resume(returning: false) }
                default: break
                }
            }

            connection.start(queue: queue)
            queue.asyncAfter(deadline: .now() + TCP_TIMEOUT) {
                guard !resumed else { return }
                resumed = true
                connection.cancel()
                continuation.resume(returning: false)
            }
        }
    }

    private func performBootstrapSend(connection: NWConnection, bootstrapKey: Data,
                                      myKey: Data, myInfo: [String: String],
                                      completion: @escaping (Bool) -> Void) {
        var packet = Data(FreedomCrypto.MAGIC_BOOTSTRAP)

        // Send key in chunks
        let chunkSize = FreedomCrypto.BOOTSTRAP_KEY_BYTES
        let totalChunks = (myKey.count + chunkSize - 1) / chunkSize
        for i in 0..<totalChunks {
            let start = i * chunkSize
            let end = min(start + chunkSize, myKey.count)
            let chunk = myKey.subdata(in: start..<end)
            let encrypted = FreedomCrypto.xorCyclic(chunk, key: bootstrapKey)

            packet.append(FreedomCrypto.BS_KEY_CHUNK)
            packet.append(UInt8((i >> 8) & 0xFF))
            packet.append(UInt8(i & 0xFF))
            packet.append(UInt8((totalChunks >> 8) & 0xFF))
            packet.append(UInt8(totalChunks & 0xFF))
            packet.append(UInt8((encrypted.count >> 8) & 0xFF))
            packet.append(UInt8(encrypted.count & 0xFF))
            packet.append(encrypted)
        }

        // Send contact info
        if let infoData = try? JSONSerialization.data(withJSONObject: myInfo) {
            let encInfo = FreedomCrypto.xorCyclic(infoData, key: bootstrapKey)
            packet.append(FreedomCrypto.BS_INFO)
            packet.append(UInt8((encInfo.count >> 8) & 0xFF))
            packet.append(UInt8(encInfo.count & 0xFF))
            packet.append(encInfo)
        }

        // Send key done
        packet.append(FreedomCrypto.BS_KEY_DONE)

        connection.send(content: packet, completion: .contentProcessed { error in
            guard error == nil else { completion(false); return }

            // Read ACK: magic(4) + type(1) = 5 bytes
            connection.receive(minimumIncompleteLength: 5, maximumLength: 5) { data, _, _, _ in
                guard let data, data.count == 5 else { completion(false); return }
                let ackType = data[4]
                completion(ackType == FreedomCrypto.BS_ACK)
            }
        })
    }

    // MARK: - Bootstrap: A delivers key to B (step 7)

    func bootstrapDeliverKey(contact: ContactData, myKey: Data, theirKey: Data,
                             bootstrapKey: Data, myInfo: [String: String]) async -> Bool {
        let ddnsList = contact.ddnsNames.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        let portsList = contact.ports.split(separator: ",").compactMap { UInt16($0.trimmingCharacters(in: .whitespaces)) }

        for ddns in ddnsList {
            for port in portsList {
                let result = await tryDeliverKey(ddns: ddns, port: port, myKey: myKey,
                                                 theirKey: theirKey, bootstrapKey: bootstrapKey, myInfo: myInfo)
                if result { return true }
            }
        }
        return false
    }

    private func tryDeliverKey(ddns: String, port: UInt16, myKey: Data, theirKey: Data,
                               bootstrapKey: Data, myInfo: [String: String]) async -> Bool {
        await withCheckedContinuation { continuation in
            let endpoint = NWEndpoint.hostPort(host: .name(ddns, nil), port: NWEndpoint.Port(integerLiteral: port))
            let connection = NWConnection(to: endpoint, using: .tcp)
            let queue = DispatchQueue(label: "com.freedom.bootstrap-deliver")
            var resumed = false

            connection.stateUpdateHandler = { state in
                guard !resumed else { return }
                switch state {
                case .ready:
                    resumed = true
                    let xored = FreedomCrypto.xorCyclic(myKey, key: theirKey)
                    connection.send(content: xored, completion: .contentProcessed { error in
                        guard error == nil else { connection.cancel(); continuation.resume(returning: false); return }

                        // Wait for ACK (3 bytes: "ACK")
                        connection.receive(minimumIncompleteLength: 3, maximumLength: 3) { data, _, _, _ in
                            // Send A's contact details (bootstrap-encrypted)
                            if let infoData = try? JSONSerialization.data(withJSONObject: myInfo) {
                                let encInfo = FreedomCrypto.xorCyclic(infoData, key: bootstrapKey)
                                var packet = Data(FreedomCrypto.MAGIC_BOOTSTRAP)
                                packet.append(FreedomCrypto.BS_INFO)
                                packet.append(UInt8((encInfo.count >> 8) & 0xFF))
                                packet.append(UInt8(encInfo.count & 0xFF))
                                packet.append(encInfo)
                                connection.send(content: packet, completion: .contentProcessed { _ in
                                    // Wait for final ACK (best-effort)
                                    connection.receive(minimumIncompleteLength: 5, maximumLength: 5) { _, _, _, _ in
                                        connection.cancel()
                                        continuation.resume(returning: true)
                                    }
                                })
                            } else {
                                connection.cancel()
                                continuation.resume(returning: true)
                            }
                        }
                    })
                case .failed, .cancelled:
                    if !resumed { resumed = true; continuation.resume(returning: false) }
                default: break
                }
            }

            connection.start(queue: queue)
            queue.asyncAfter(deadline: .now() + TCP_TIMEOUT) {
                guard !resumed else { return }
                resumed = true
                connection.cancel()
                continuation.resume(returning: false)
            }
        }
    }

    // MARK: - Share handlers

    private func handleShareReq(_ content: String) {
        let contactId = contact.id ?? 0
        // content format: {shareId}:{otherContactName}:{message}
        let parts = content.split(separator: ":", maxSplits: 2).map(String.init)
        guard parts.count >= 2 else { return }
        let shareId = parts[0]
        let otherName = parts[1]
        let message = parts.count >= 3 ? parts[2] : ""
        ContactShareEngine.shared.handleShareRequest(
            fromContactId: contactId,
            shareId: shareId,
            otherName: otherName,
            message: message
        )
    }

    // MARK: - Helpers

    private func storeIncomingKey(_ encodedKey: String) {
        do {
            guard var fresh = try db.findContactByDdns(contact.ddnsNames.split(separator: ",").first.map(String.init) ?? "") else { return }
            let encrypted = PasskeySession.shared.encryptField(encodedKey) ?? encodedKey
            let slot = fresh.firstEmptyRecvSlot
            guard slot >= 0 else { return }
            fresh = fresh.withRecvKeyInSlot(slot, key: encrypted)
            _ = try db.insertContact(fresh)
        } catch {
            logger.error("storeIncomingKey failed: \(error.localizedDescription)")
        }
    }

    private static let dateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd HH:mm:ss"
        f.locale = Locale(identifier: "en_US_POSIX")
        return f
    }()
}
