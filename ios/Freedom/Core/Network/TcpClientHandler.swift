// ═════════════════════════════════════════════════════════════════════════════
//  TcpClientHandler — Per-connection handler for inbound TCP clients
// ═════════════════════════════════════════════════════════════════════════════
//
//  Connection type is detected by peeking the first 2 bytes:
//    - 0xFF 0xFF → Bootstrap handshake (new contact exchange)
//    - Pending reverse contact → Key delivery (step 7 of exchange)
//    - Otherwise → Normal encrypted message connection
//
// ═════════════════════════════════════════════════════════════════════════════

import Foundation
import Network
import os.log

private let HEARTBEAT_INTERVAL: TimeInterval = 30

final class TcpClientHandler {

    private let connection: NWConnection
    private let firstTwoBytes: Data
    private let logger = Logger(subsystem: "com.freedom", category: "TcpClientHandler")
    private let db = FreedomDatabase.shared
    private var resolvedContact: ContactData?
    private var otpChannel: OtpChannel?
    private var heartbeatTimer: DispatchSourceTimer?
    private let queue = DispatchQueue(label: "com.freedom.tcp-handler", qos: .userInitiated)

    init(connection: NWConnection, firstTwoBytes: Data) {
        self.connection = connection
        self.firstTwoBytes = firstTwoBytes
    }

    func run() {
        let bytes = [UInt8](firstTwoBytes)
        if bytes[0] == 0xFF && bytes[1] == 0xFF {
            handleBootstrap()
        } else if BootstrapKeyHolder.shared.pendingReverseContact != nil {
            handleKeyDelivery()
        } else {
            handleNormalConnection()
        }
    }

    // MARK: - Bootstrap Mode

    private func handleBootstrap() {
        // Check for share bootstrap key first, then fall back to normal QR bootstrap key
        let bootstrapKey: Data
        if let shareKey = BootstrapKeyHolder.shared.shareBootstrapKey {
            bootstrapKey = shareKey
        } else if let activeKey = BootstrapKeyHolder.shared.activeBootstrapKey {
            bootstrapKey = activeKey
        } else {
            logger.warning("Bootstrap packet but no active bootstrap key")
            connection.cancel()
            return
        }

        // Read remaining 2 bytes of magic header (we already have FF FF)
        connection.receive(minimumIncompleteLength: 2, maximumLength: 2) { [weak self] data, _, _, error in
            guard let self, let data, data.count == 2 else { self?.connection.cancel(); return }
            let magic34 = [UInt8](data)
            guard magic34[0] == 0x42, magic34[1] == 0x53 else {
                self.logger.warning("Invalid bootstrap magic")
                self.connection.cancel()
                return
            }
            self.readBootstrapPackets(bootstrapKey: bootstrapKey)
        }
    }

    private func readBootstrapPackets(bootstrapKey: Data) {
        var keyBuffer = Data(count: FreedomCrypto.MESSAGE_KEY_BYTES)
        var keyOffset = 0
        var contactName = ""
        var contactDdns = ""
        var contactPorts = ""

        func readNextPacket() {
            // Read packet type (1 byte)
            connection.receive(minimumIncompleteLength: 1, maximumLength: 1) { [weak self] data, _, _, _ in
                guard let self, let data, !data.isEmpty else { self?.connection.cancel(); return }
                let type = data[0]

                switch type {
                case FreedomCrypto.BS_KEY_CHUNK:
                    // Read seq(2) + total(2) + len(2) = 6 bytes
                    self.connection.receive(minimumIncompleteLength: 6, maximumLength: 6) { data, _, _, _ in
                        guard let data, data.count == 6 else { self.connection.cancel(); return }
                        let len = Int(data[4]) << 8 | Int(data[5])
                        // Read payload
                        self.connection.receive(minimumIncompleteLength: len, maximumLength: len) { payload, _, _, _ in
                            guard let payload, payload.count == len else { self.connection.cancel(); return }
                            let decrypted = FreedomCrypto.xorCyclic(payload, key: bootstrapKey)
                            keyBuffer.replaceSubrange(keyOffset..<keyOffset + decrypted.count, with: decrypted)
                            keyOffset += decrypted.count
                            readNextPacket()
                        }
                    }

                case FreedomCrypto.BS_INFO:
                    // Read len(2)
                    self.connection.receive(minimumIncompleteLength: 2, maximumLength: 2) { data, _, _, _ in
                        guard let data, data.count == 2 else { self.connection.cancel(); return }
                        let len = Int(data[0]) << 8 | Int(data[1])
                        self.connection.receive(minimumIncompleteLength: len, maximumLength: len) { payload, _, _, _ in
                            guard let payload, payload.count == len else { self.connection.cancel(); return }
                            let decrypted = FreedomCrypto.xorCyclic(payload, key: bootstrapKey)
                            if let json = try? JSONSerialization.jsonObject(with: decrypted) as? [String: Any] {
                                contactName = json["name"] as? String ?? ""
                                contactDdns = json["ddns"] as? String ?? ""
                                contactPorts = json["ports"] as? String ?? ""
                            }
                            readNextPacket()
                        }
                    }

                case FreedomCrypto.BS_KEY_DONE:
                    self.finishBootstrap(keyBuffer: keyBuffer, keyOffset: keyOffset,
                                        name: contactName, ddns: contactDdns, ports: contactPorts)

                default:
                    self.logger.warning("Unknown bootstrap type: \(type)")
                    self.connection.cancel()
                }
            }
        }

        readNextPacket()
    }

    private func finishBootstrap(keyBuffer: Data, keyOffset: Int, name: String, ddns: String, ports: String) {
        guard keyOffset == FreedomCrypto.MESSAGE_KEY_BYTES else {
            logger.warning("Bootstrap key size mismatch: \(keyOffset)")
            connection.cancel()
            return
        }

        let recvKeyB64 = keyBuffer.base64EncodedString()
        let encRecvKey = PasskeySession.shared.encryptField(recvKeyB64) ?? recvKeyB64

        do {
            let existing = !ddns.isEmpty ? try db.findContactByDdns(ddns.split(separator: ",").first.map(String.init) ?? "") : nil

            var contact = existing ?? ContactData(
                name: name.isEmpty ? "Unknown" : name,
                ddnsNames: ddns,
                ports: ports
            )
            contact.name = name.isEmpty ? contact.name : name
            contact.ddnsNames = ddns.isEmpty ? contact.ddnsNames : ddns
            contact.ports = ports.isEmpty ? contact.ports : ports
            contact.recvKey0 = encRecvKey
            contact.recvKeyCreatedAt0 = Int64(Date().timeIntervalSince1970 * 1000)
            contact.activeRecvKeyIdx = 0

            contact = try db.insertContact(contact)
            resolvedContact = contact

            // Send ACK
            var ack = Data(FreedomCrypto.MAGIC_BOOTSTRAP)
            ack.append(FreedomCrypto.BS_ACK)
            connection.send(content: ack, completion: .contentProcessed { [weak self] _ in
                if let contact = self?.resolvedContact {
                    BootstrapKeyHolder.shared.onHandshakeComplete?(contact)
                }
                // Clear share bootstrap if this was a share-initiated bootstrap
                // and cancel the 30-second timeout
                if BootstrapKeyHolder.shared.shareBootstrapKey != nil {
                    ContactShareEngine.shared.bootstrapCompleted()
                }
                BootstrapKeyHolder.shared.clearShareBootstrap()
                self?.connection.cancel()
            })
        } catch {
            logger.error("Bootstrap save failed: \(error.localizedDescription)")
            connection.cancel()
        }
    }

    // MARK: - Key Delivery Mode

    private func handleKeyDelivery() {
        guard let pendingContact = BootstrapKeyHolder.shared.pendingReverseContact else {
            connection.cancel()
            return
        }

        // Read remaining bytes of 24KB key
        let remaining = FreedomCrypto.MESSAGE_KEY_BYTES - 2
        connection.receive(minimumIncompleteLength: remaining, maximumLength: remaining) { [weak self] data, _, _, _ in
            guard let self, let data, data.count == remaining else { self?.connection.cancel(); return }

            var rawData = self.firstTwoBytes
            rawData.append(data)

            // XOR with our send key to recover recv key
            guard let plainSendKey = PasskeySession.shared.decryptField(pendingContact.activeSendKey),
                  !plainSendKey.isEmpty,
                  let sendKeyBytes = Data(base64Encoded: plainSendKey) else {
                self.connection.cancel()
                return
            }

            let recvKeyBytes = FreedomCrypto.xorCyclic(rawData, key: sendKeyBytes)
            let recvKeyB64 = recvKeyBytes.base64EncodedString()
            let encRecvKey = PasskeySession.shared.encryptField(recvKeyB64) ?? recvKeyB64

            // Send ACK
            let ack = Data([0x41, 0x43, 0x4B]) // "ACK"
            self.connection.send(content: ack, completion: .contentProcessed { _ in
                // Read A's contact details (bootstrap-encrypted)
                self.readDeliveryContactInfo(pendingContact: pendingContact, encRecvKey: encRecvKey)
            })
        }
    }

    private func readDeliveryContactInfo(pendingContact: ContactData, encRecvKey: String) {
        let bsKey = BootstrapKeyHolder.shared.scannedBootstrapKey

        // Try to read magic header + BS_INFO
        connection.receive(minimumIncompleteLength: 4, maximumLength: 4) { [weak self] data, _, _, _ in
            guard let self, let data, data.count == 4, let bsKey else {
                // Best-effort — save what we have
                self?.saveKeyDeliveryResult(pendingContact: pendingContact, encRecvKey: encRecvKey,
                                           name: nil, ddns: nil, ports: nil)
                return
            }

            let magic = [UInt8](data)
            guard magic[0] == 0xFF, magic[1] == 0xFF, magic[2] == 0x42, magic[3] == 0x53 else {
                self.saveKeyDeliveryResult(pendingContact: pendingContact, encRecvKey: encRecvKey,
                                          name: nil, ddns: nil, ports: nil)
                return
            }

            // Read type(1) + len(2)
            self.connection.receive(minimumIncompleteLength: 3, maximumLength: 3) { data, _, _, _ in
                guard let data, data.count == 3 else {
                    self.saveKeyDeliveryResult(pendingContact: pendingContact, encRecvKey: encRecvKey,
                                              name: nil, ddns: nil, ports: nil)
                    return
                }

                let type = data[0]
                let len = Int(data[1]) << 8 | Int(data[2])

                guard type == FreedomCrypto.BS_INFO else {
                    self.saveKeyDeliveryResult(pendingContact: pendingContact, encRecvKey: encRecvKey,
                                              name: nil, ddns: nil, ports: nil)
                    return
                }

                self.connection.receive(minimumIncompleteLength: len, maximumLength: len) { payload, _, _, _ in
                    var aName: String?
                    var aDdns: String?
                    var aPorts: String?

                    if let payload, payload.count == len {
                        let decrypted = FreedomCrypto.xorCyclic(payload, key: bsKey)
                        if let json = try? JSONSerialization.jsonObject(with: decrypted) as? [String: Any] {
                            aName = json["name"] as? String
                            aDdns = json["ddns"] as? String
                            aPorts = json["ports"] as? String
                        }
                    }

                    self.saveKeyDeliveryResult(pendingContact: pendingContact, encRecvKey: encRecvKey,
                                              name: aName, ddns: aDdns, ports: aPorts)
                }
            }
        }
    }

    private func saveKeyDeliveryResult(pendingContact: ContactData, encRecvKey: String,
                                       name: String?, ddns: String?, ports: String?) {
        do {
            var updated = pendingContact
            if let name, !name.isEmpty { updated.name = name }
            if let ddns, !ddns.isEmpty { updated.ddnsNames = ddns }
            if let ports, !ports.isEmpty { updated.ports = ports }
            updated.recvKey0 = encRecvKey
            updated.recvKeyCreatedAt0 = Int64(Date().timeIntervalSince1970 * 1000)
            updated.activeRecvKeyIdx = 0

            updated = try db.insertContact(updated)
            resolvedContact = updated

            // Send final ACK
            var ack = Data(FreedomCrypto.MAGIC_BOOTSTRAP)
            ack.append(FreedomCrypto.BS_ACK)
            connection.send(content: ack, completion: .contentProcessed { [weak self] _ in
                if let contact = self?.resolvedContact {
                    BootstrapKeyHolder.shared.onHandshakeComplete?(contact)
                }
                BootstrapKeyHolder.shared.pendingReverseContact = nil
                BootstrapKeyHolder.shared.scannedBootstrapKey = nil
                self?.connection.cancel()
            })
        } catch {
            logger.error("Key delivery save failed: \(error.localizedDescription)")
            connection.cancel()
        }
    }

    // MARK: - Normal OTP Connection

    private func handleNormalConnection() {
        // Reconstruct first line: read until \n
        var lineBuffer = firstTwoBytes

        func readUntilNewline() {
            connection.receive(minimumIncompleteLength: 1, maximumLength: 4096) { [weak self] data, _, isComplete, _ in
                guard let self, let data else { self?.connection.cancel(); return }

                lineBuffer.append(data)

                if let newlineIdx = lineBuffer.firstIndex(of: UInt8(ascii: "\n")) {
                    let lineData = lineBuffer.prefix(upTo: newlineIdx)
                    let firstLine = String(data: Data(lineData), encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""

                    // Leftover bytes after first newline
                    let remaining = Data(lineBuffer.suffix(from: lineBuffer.index(after: newlineIdx)))

                    self.identifyContactAndStartLoop(firstLine: firstLine, leftover: remaining)
                } else if isComplete {
                    let firstLine = String(data: lineBuffer, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                    self.identifyContactAndStartLoop(firstLine: firstLine, leftover: Data())
                } else {
                    readUntilNewline()
                }
            }
        }

        readUntilNewline()
    }

    private func identifyContactAndStartLoop(firstLine: String, leftover: Data) {
        // Identify contact by trying to decrypt with each contact's recv key
        let contacts = (try? db.getAllContacts()) ?? []
        let threshold = UserDefaults.standard.integer(forKey: "key_rotation_threshold")
        let effectiveThreshold = threshold > 0 ? threshold : FreedomCrypto.DEFAULT_ROTATION_THRESHOLD

        for contact in contacts {
            guard let plainRecvKey = PasskeySession.shared.decryptField(contact.activeRecvKey),
                  !plainRecvKey.isEmpty,
                  let recvKeyBytes = Data(base64Encoded: plainRecvKey) else { continue }

            let plainSendKey = PasskeySession.shared.decryptField(contact.activeSendKey) ?? ""
            let sendKeyBytes = plainSendKey.isEmpty ? Data() : (Data(base64Encoded: plainSendKey) ?? Data())

            let ch = OtpChannel(contactId: contact.id ?? 0, sendKey: sendKeyBytes,
                                recvKey: recvKeyBytes, rotationThreshold: effectiveThreshold)
            if let decrypted = ch.decrypt(firstLine) {
                resolvedContact = contact
                otpChannel = ch
                processMessage(decrypted)
                break
            }
        }

        // Register inbound connection
        if let contact = resolvedContact, let channel = otpChannel {
            ContactConnectionManager.shared.registerInbound(
                contactId: contact.id ?? 0,
                connection: connection,
                channel: channel
            )
        }

        // Start heartbeat
        startHeartbeat()

        // Start message read loop
        startReadLoop(leftover: leftover)
    }

    private func startHeartbeat() {
        heartbeatTimer = DispatchSource.makeTimerSource(queue: queue)
        heartbeatTimer?.schedule(deadline: .now() + HEARTBEAT_INTERVAL, repeating: HEARTBEAT_INTERVAL)
        heartbeatTimer?.setEventHandler { [weak self] in
            self?.sendLine("PING")
        }
        heartbeatTimer?.resume()
    }

    private func startReadLoop(leftover: Data) {
        var buffer = leftover

        func processBuffer() {
            while let newlineIdx = buffer.firstIndex(of: UInt8(ascii: "\n")) {
                let lineData = buffer.prefix(upTo: newlineIdx)
                buffer = Data(buffer.suffix(from: buffer.index(after: newlineIdx)))
                let raw = String(data: Data(lineData), encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                if raw.isEmpty { continue }

                // File chunks bypass OtpChannel
                if raw.hasPrefix("FCHUNK:") {
                    if let contact = resolvedContact {
                        FileTransferEngine.shared.handleChunk(contactId: contact.id ?? 0, rawLine: raw)
                    }
                    continue
                }

                let decrypted = otpChannel?.decrypt(raw) ?? raw
                processMessage(decrypted)
            }
        }

        func readMore() {
            connection.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] data, _, isComplete, error in
                guard let self else { return }
                if let data {
                    buffer.append(data)
                    processBuffer()
                }
                if isComplete || error != nil {
                    self.cleanup()
                } else {
                    readMore()
                }
            }
        }

        processBuffer()
        readMore()
    }

    private func processMessage(_ decrypted: String) {
        guard let parsed = MessageParser.parse(decrypted) else { return }

        switch parsed.type {
        case .PING:
            sendLine("PONG")
            if let id = resolvedContact?.id { ContactConnectionManager.shared.heartbeat(contactId: id) }

        case .PONG:
            if let id = resolvedContact?.id { ContactConnectionManager.shared.heartbeat(contactId: id) }

        case .SEARCH_REQUEST:
            handleSearchRequest()

        case .KEY_ROTATE_DELIVERY:
            handleKeyRotateDelivery(parsed.content)

        case .INFRA_DDNS_UPDATE, .INFRA_PORT_UPDATE:
            handleEndpointUpdate(parsed.type, content: parsed.content)
            sendEncrypted("INFRA:ACK")

        case .INFRA_FILE_START:
            if let contact = resolvedContact {
                FileTransferEngine.shared.onFileStart(contactId: contact.id ?? 0, payload: parsed.content)
            }

        case .INFRA_FILE_DONE:
            if let contact = resolvedContact {
                FileTransferEngine.shared.onFileDone(contactId: contact.id ?? 0,
                                                     contactName: contact.name, payload: parsed.content)
            }

        case .INFRA_FILE_ACK, .INFRA_FILE_ERROR:
            break

        case .SHARE_REQ:
            handleShareReq(parsed.content)

        case .SHARE_APPROVE:
            if let contactId = resolvedContact?.id {
                ContactShareEngine.shared.handleShareApprove(fromContactId: contactId, shareId: parsed.content)
            }

        case .SHARE_DENY:
            if let contactId = resolvedContact?.id {
                ContactShareEngine.shared.handleShareDeny(fromContactId: contactId, shareId: parsed.content)
            }

        case .SHARE_CONNECT:
            if let contactId = resolvedContact?.id {
                ContactShareEngine.shared.handleShareConnect(fromContactId: contactId, payload: parsed.content)
            }

        case .SHARE_FAIL:
            if let contactId = resolvedContact?.id {
                ContactShareEngine.shared.handleShareFail(fromContactId: contactId, payload: parsed.content)
            }

        default:
            // Persist user-visible message
            let timestamp = Self.dateFormatter.string(from: Date())
            let msg = MessageData(
                timestamp: timestamp,
                messageType: parsed.type.rawValue,
                content: parsed.content,
                sender: resolvedContact?.name,
                contactId: resolvedContact?.id ?? 0,
                direction: MessageData.RECEIVED
            )
            _ = try? db.insertMessage(msg)
        }
    }

    // MARK: - Control message handlers

    private func handleSearchRequest() {
        let amSearchable = UserDefaults.standard.bool(forKey: "my_searchable")
        let contacts = (try? db.getAllContacts().filter(\.isSearchable).map { ["name": $0.name] }) ?? []
        let payload: [String: Any] = ["searchable": amSearchable, "contacts": contacts]
        if let json = try? JSONSerialization.data(withJSONObject: payload),
           let jsonStr = String(data: json, encoding: .utf8) {
            sendEncrypted("SRCH:RESP:\(jsonStr)")
        }
    }

    private func handleKeyRotateDelivery(_ encodedKey: String) {
        guard let contact = resolvedContact else { return }
        do {
            let fresh = try db.findContactByDdns(contact.ddnsNames.split(separator: ",").first.map(String.init) ?? "")
            guard var fresh else { return }
            let encrypted = PasskeySession.shared.encryptField(encodedKey) ?? encodedKey
            let slot = fresh.firstEmptyRecvSlot
            guard slot >= 0 else { return }
            fresh = fresh.withRecvKeyInSlot(slot, key: encrypted)
            _ = try db.insertContact(fresh)
        } catch {
            logger.error("handleKeyRotateDelivery failed: \(error.localizedDescription)")
        }
    }

    private func handleEndpointUpdate(_ type: MessageType, content: String) {
        guard let contact = resolvedContact,
              let data = content.data(using: .utf8),
              let map = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return }

        do {
            guard var fresh = try db.findContactByDdns(contact.ddnsNames.split(separator: ",").first.map(String.init) ?? "") else { return }

            switch type {
            case .INFRA_DDNS_UPDATE:
                guard let newDdns = map["ddns"] as? String else { return }
                let existing = fresh.ddnsNames.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }
                if !existing.contains(newDdns) {
                    fresh.ddnsNames = (existing + [newDdns]).joined(separator: ",")
                    _ = try db.insertContact(fresh)
                }
            case .INFRA_PORT_UPDATE:
                guard let newPort = (map["port"] as? NSNumber)?.stringValue else { return }
                let existing = fresh.ports.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }
                if !existing.contains(newPort) {
                    fresh.ports = (existing + [newPort]).joined(separator: ",")
                    _ = try db.insertContact(fresh)
                }
            default: break
            }
        } catch {
            logger.error("handleEndpointUpdate failed: \(error.localizedDescription)")
        }
    }

    // MARK: - Share handlers

    private func handleShareReq(_ content: String) {
        guard let contactId = resolvedContact?.id else { return }
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

    // MARK: - Send helpers

    private func sendLine(_ text: String) {
        let line = text + "\r\n"
        connection.send(content: line.data(using: .utf8), completion: .contentProcessed { _ in })
    }

    private func sendEncrypted(_ plaintext: String) {
        let line = otpChannel?.encrypt(plaintext) ?? plaintext
        sendLine(line)
    }

    private func cleanup() {
        heartbeatTimer?.cancel()
        heartbeatTimer = nil
        if let contact = resolvedContact {
            ContactConnectionManager.shared.unregisterInbound(contactId: contact.id ?? 0)
        }
        connection.cancel()
    }

    private static let dateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd HH:mm:ss"
        f.locale = Locale(identifier: "en_US_POSIX")
        return f
    }()
}
