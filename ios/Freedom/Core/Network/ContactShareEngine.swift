// =============================================================================
//  ContactShareEngine — Introduces two contacts to each other
// =============================================================================
//
//  Flow (A = this device, B = listener, C = connector):
//
//   1. A sends SHARE_REQ to both B and C
//   2. Both approve (SHARE_APPROVE)
//   3. A generates a 256-byte bootstrap key
//   4. A sends B: SHARE_CONNECT with bootstrap key + C's name (B listens)
//   5. A sends C: SHARE_CONNECT with bootstrap key + B's DDNS + B's port + B's name (C connects)
//   6. C connects directly to B, normal 7-phase bootstrap
//   7. B and C now have their own OTP channel
//
// =============================================================================

import Foundation
import os.log

final class ContactShareEngine: ObservableObject {

    static let shared = ContactShareEngine()

    private let logger = Logger(subsystem: "com.freedom", category: "ContactShareEngine")
    private let db = FreedomDatabase.shared
    private let lock = NSLock()

    // MARK: - Types

    struct ShareState {
        let shareId: String
        let contact1Id: Int64   // B (listener)
        let contact2Id: Int64   // C (connector)
        var contact1Approved = false
        var contact2Approved = false
        let message: String
        let createdAt: Date
    }

    enum ShareEvent {
        case requested(shareId: String, otherName: String, message: String)
        case approved(shareId: String)
        case denied(shareId: String)
        case connectAsListener(shareId: String, bootstrapKey: Data, otherName: String)
        case connectAsConnector(shareId: String, bootstrapKey: Data, ddns: String, port: UInt16, otherName: String)
        case completed(shareId: String)
        case failed(shareId: String, reason: String)
    }

    // MARK: - Published state

    /// Shares this device (A) has initiated.
    @Published var pendingShares: [String: ShareState] = [:]

    /// Incoming share requests this device needs to approve/deny.
    @Published var incomingRequests: [(shareId: String, fromContactId: Int64, otherName: String, message: String)] = []

    /// Recent events for UI display.
    @Published var lastEvent: ShareEvent?

    // MARK: - Rate limiting

    private var lastShareTime: [Int64: Date] = [:]
    var rateLimitSeconds: TimeInterval = 15

    // MARK: - Timeout

    private static let SHARE_TIMEOUT: TimeInterval = 300 // 5 minutes
    private static let BOOTSTRAP_TIMEOUT: TimeInterval = 30
    private var cleanupTimer: Timer?

    /// Tracks the share ID for the current bootstrap-wait, so the timeout can be cancelled on success.
    private var pendingBootstrapShareId: String?
    /// Contact ID of the initiator (A) who sent SHARE_CONNECT, used to send SHARE_FAIL back.
    private var pendingBootstrapFromContactId: Int64?
    /// Work item for the 30-second bootstrap timeout, so it can be cancelled if bootstrap completes in time.
    private var bootstrapTimeoutWork: DispatchWorkItem?

    private init() {
        startCleanupTimer()
    }

    // MARK: - A initiates a share: introduce contact1 (B) to contact2 (C)

    /// Returns true if the share request was sent, false if rate-limited or contacts invalid.
    func initiateShare(contact1Id: Int64, contact2Id: Int64, message: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }

        // Rate limiting: check both contacts
        let now = Date()
        if let last1 = lastShareTime[contact1Id],
           now.timeIntervalSince(last1) < rateLimitSeconds {
            logger.warning("Rate limited: contact1 \(contact1Id)")
            return false
        }
        if let last2 = lastShareTime[contact2Id],
           now.timeIntervalSince(last2) < rateLimitSeconds {
            logger.warning("Rate limited: contact2 \(contact2Id)")
            return false
        }

        // Validate contacts exist
        guard let contact1 = try? db.findContactById(contact1Id),
              let contact2 = try? db.findContactById(contact2Id) else {
            logger.error("Cannot share: one or both contacts not found")
            return false
        }

        let shareId = UUID().uuidString

        let state = ShareState(
            shareId: shareId,
            contact1Id: contact1Id,
            contact2Id: contact2Id,
            contact1Approved: false,
            contact2Approved: false,
            message: message,
            createdAt: now
        )
        pendingShares[shareId] = state

        lastShareTime[contact1Id] = now
        lastShareTime[contact2Id] = now

        // Send SHARE_REQ to contact1 (B): tell B about C
        let req1 = "INFRA:SHARE_REQ:\(shareId):\(contact2.name):\(message)"
        ContactConnectionManager.shared.send(contactId: contact1Id, plaintext: req1)

        // Send SHARE_REQ to contact2 (C): tell C about B
        let req2 = "INFRA:SHARE_REQ:\(shareId):\(contact1.name):\(message)"
        ContactConnectionManager.shared.send(contactId: contact2Id, plaintext: req2)

        logger.info("Share initiated: \(shareId) — \(contact1.name) <-> \(contact2.name)")
        return true
    }

    // MARK: - Handle incoming SHARE_REQ (this device is B or C)

    func handleShareRequest(fromContactId: Int64, shareId: String, otherName: String, message: String) {
        lock.lock()
        defer { lock.unlock() }

        logger.info("Received share request \(shareId) from contact \(fromContactId), other=\(otherName)")

        incomingRequests.append((
            shareId: shareId,
            fromContactId: fromContactId,
            otherName: otherName,
            message: message
        ))

        DispatchQueue.main.async { [weak self] in
            self?.lastEvent = .requested(shareId: shareId, otherName: otherName, message: message)
        }
    }

    // MARK: - This device approves a share request

    func approveShare(shareId: String, fromContactId: Int64) {
        lock.lock()
        incomingRequests.removeAll { $0.shareId == shareId }
        lock.unlock()

        let msg = "INFRA:SHARE_APPROVE:\(shareId)"
        ContactConnectionManager.shared.send(contactId: fromContactId, plaintext: msg)
        logger.info("Approved share \(shareId)")
    }

    // MARK: - This device denies a share request

    func denyShare(shareId: String, fromContactId: Int64) {
        lock.lock()
        incomingRequests.removeAll { $0.shareId == shareId }
        lock.unlock()

        let msg = "INFRA:SHARE_DENY:\(shareId)"
        ContactConnectionManager.shared.send(contactId: fromContactId, plaintext: msg)
        logger.info("Denied share \(shareId)")
    }

    // MARK: - Handle SHARE_APPROVE (A receives approval from B or C)

    func handleShareApprove(fromContactId: Int64, shareId: String) {
        lock.lock()
        guard var state = pendingShares[shareId] else {
            lock.unlock()
            logger.warning("Approve for unknown share: \(shareId)")
            return
        }

        if fromContactId == state.contact1Id {
            state.contact1Approved = true
        } else if fromContactId == state.contact2Id {
            state.contact2Approved = true
        } else {
            lock.unlock()
            logger.warning("Approve from unexpected contact \(fromContactId) for share \(shareId)")
            return
        }

        pendingShares[shareId] = state

        let bothApproved = state.contact1Approved && state.contact2Approved
        let stateCopy = state
        lock.unlock()

        DispatchQueue.main.async { [weak self] in
            self?.lastEvent = .approved(shareId: shareId)
        }

        if bothApproved {
            logger.info("Both contacts approved share \(shareId) — generating keys")
            generateAndSendKeys(state: stateCopy)
        }
    }

    // MARK: - Handle SHARE_DENY (A receives denial from B or C)

    func handleShareDeny(fromContactId: Int64, shareId: String) {
        lock.lock()
        pendingShares.removeValue(forKey: shareId)
        lock.unlock()

        logger.info("Share \(shareId) denied by contact \(fromContactId)")

        DispatchQueue.main.async { [weak self] in
            self?.lastEvent = .denied(shareId: shareId)
        }
    }

    // MARK: - Handle SHARE_CONNECT (B or C receives connection info from A)

    /// Payload format for listener (B): {shareId}:{base64Key}:{otherName}
    /// Payload format for connector (C): {shareId}:{base64Key}:{ddns}:{port}:{otherName}
    func handleShareConnect(fromContactId: Int64, payload: String) {
        let parts = payload.split(separator: ":", maxSplits: 5).map(String.init)

        guard parts.count >= 3 else {
            logger.error("SHARE_CONNECT: invalid payload (too few parts): \(payload)")
            return
        }

        let shareId = parts[0]
        let base64Key = parts[1]

        guard let bootstrapKey = Data(base64Encoded: base64Key) else {
            logger.error("SHARE_CONNECT: invalid bootstrap key")
            return
        }

        if parts.count == 3 {
            // Listener (B): {shareId}:{base64Key}:{otherName}
            let otherName = parts[2]
            logger.info("Share connect as LISTENER: \(shareId), expecting \(otherName)")

            // Store the bootstrap key so TcpClientHandler can accept this bootstrap
            BootstrapKeyHolder.shared.shareBootstrapKey = bootstrapKey
            BootstrapKeyHolder.shared.shareExpectedName = otherName

            // Track who sent us this SHARE_CONNECT so we can send SHARE_FAIL back on timeout
            pendingBootstrapShareId = shareId
            pendingBootstrapFromContactId = fromContactId

            // Schedule a 30-second timeout for bootstrap completion
            scheduleBootstrapTimeout(shareId: shareId, fromContactId: fromContactId)

            DispatchQueue.main.async { [weak self] in
                self?.lastEvent = .connectAsListener(shareId: shareId, bootstrapKey: bootstrapKey, otherName: otherName)
            }

        } else if parts.count == 5 {
            // Connector (C): {shareId}:{base64Key}:{ddns}:{port}:{otherName}
            let ddns = parts[2]
            guard let port = UInt16(parts[3]) else {
                logger.error("SHARE_CONNECT: invalid port")
                return
            }
            let otherName = parts[4]
            logger.info("Share connect as CONNECTOR: \(shareId), connecting to \(ddns):\(port) (\(otherName))")

            DispatchQueue.main.async { [weak self] in
                self?.lastEvent = .connectAsConnector(shareId: shareId, bootstrapKey: bootstrapKey,
                                                      ddns: ddns, port: port, otherName: otherName)
            }

            // Initiate the bootstrap connection to B
            initiateShareBootstrap(shareId: shareId, bootstrapKey: bootstrapKey,
                                   ddns: ddns, port: port, otherName: otherName)

        } else {
            logger.error("SHARE_CONNECT: unexpected part count \(parts.count)")
        }
    }

    // MARK: - Check if an incoming bootstrap matches a share flow

    func isExpectedShareBootstrap() -> Bool {
        return BootstrapKeyHolder.shared.shareBootstrapKey != nil
    }

    // MARK: - Bootstrap completed successfully — cancel the timeout

    func bootstrapCompleted() {
        lock.lock()
        let shareId = pendingBootstrapShareId
        pendingBootstrapShareId = nil
        pendingBootstrapFromContactId = nil
        lock.unlock()

        bootstrapTimeoutWork?.cancel()
        bootstrapTimeoutWork = nil

        if let shareId {
            logger.info("Bootstrap completed in time for share \(shareId)")
        }
    }

    // MARK: - Handle SHARE_FAIL (initiator A receives timeout notification from B)

    /// Called when this device (as initiator A or connector C) receives SHARE_FAIL from the listener.
    /// Payload format: {shareId}:{reason}
    func handleShareFail(fromContactId: Int64, payload: String) {
        let parts = payload.split(separator: ":", maxSplits: 1).map(String.init)
        let shareId = parts.first ?? ""
        let reason = parts.count >= 2 ? parts[1] : "unknown"

        logger.warning("Received SHARE_FAIL for \(shareId): \(reason)")

        lock.lock()
        pendingShares.removeValue(forKey: shareId)
        lock.unlock()

        DispatchQueue.main.async { [weak self] in
            self?.lastEvent = .failed(shareId: shareId, reason: "Sharing failed - Timeout")
        }
    }

    // MARK: - Private: schedule / cancel bootstrap timeout

    private func scheduleBootstrapTimeout(shareId: String, fromContactId: Int64) {
        // Cancel any previous timeout
        bootstrapTimeoutWork?.cancel()

        let work = DispatchWorkItem { [weak self] in
            guard let self else { return }

            self.lock.lock()
            let stillPending = self.pendingBootstrapShareId == shareId
            if stillPending {
                self.pendingBootstrapShareId = nil
                self.pendingBootstrapFromContactId = nil
            }
            self.lock.unlock()

            guard stillPending else { return }

            self.logger.warning("Bootstrap timeout for share \(shareId) — cleaning up")

            // Clean up the expected bootstrap state
            BootstrapKeyHolder.shared.clearShareBootstrap()

            // Send SHARE_FAIL back to the contact (A) who sent SHARE_CONNECT
            let failMsg = "INFRA:SHARE_FAIL:\(shareId):timeout"
            ContactConnectionManager.shared.send(contactId: fromContactId, plaintext: failMsg)

            DispatchQueue.main.async { [weak self] in
                self?.lastEvent = .failed(shareId: shareId, reason: "Sharing failed - Timeout")
            }
        }

        bootstrapTimeoutWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.BOOTSTRAP_TIMEOUT, execute: work)
    }

    // MARK: - Private: generate bootstrap key and send SHARE_CONNECT to both parties

    private func generateAndSendKeys(state: ShareState) {
        // Generate a 256-byte bootstrap key
        var bootstrapKey = Data(count: FreedomCrypto.BOOTSTRAP_KEY_BYTES)
        bootstrapKey.withUnsafeMutableBytes { ptr in
            _ = SecRandomCopyBytes(kSecRandomDefault, FreedomCrypto.BOOTSTRAP_KEY_BYTES, ptr.baseAddress!)
        }
        let base64Key = bootstrapKey.base64EncodedString()

        // Look up contact2 (C) details to send to B for the connect info
        guard let contact1 = try? db.findContactById(state.contact1Id),
              let contact2 = try? db.findContactById(state.contact2Id) else {
            logger.error("generateAndSendKeys: contacts not found")
            lock.lock()
            pendingShares.removeValue(forKey: state.shareId)
            lock.unlock()
            DispatchQueue.main.async { [weak self] in
                self?.lastEvent = .failed(shareId: state.shareId, reason: "Contacts not found")
            }
            return
        }

        // Send to B (listener): SHARE_CONNECT:{shareId}:{base64Key}:{otherContactName}
        let msgToB = "INFRA:SHARE_CONNECT:\(state.shareId):\(base64Key):\(contact2.name)"
        ContactConnectionManager.shared.send(contactId: state.contact1Id, plaintext: msgToB)

        // Send to C (connector): SHARE_CONNECT:{shareId}:{base64Key}:{ddns}:{port}:{otherContactName}
        // Use B's first DDNS and first port
        let bDdns = contact1.ddnsNames.split(separator: ",").first.map(String.init)?.trimmingCharacters(in: .whitespaces) ?? ""
        let bPort = contact1.ports.split(separator: ",").first.map(String.init)?.trimmingCharacters(in: .whitespaces) ?? ""

        let msgToC = "INFRA:SHARE_CONNECT:\(state.shareId):\(base64Key):\(bDdns):\(bPort):\(contact1.name)"
        ContactConnectionManager.shared.send(contactId: state.contact2Id, plaintext: msgToC)

        logger.info("Sent SHARE_CONNECT to both parties for share \(state.shareId)")

        // Clean up the pending share
        lock.lock()
        pendingShares.removeValue(forKey: state.shareId)
        lock.unlock()

        DispatchQueue.main.async { [weak self] in
            self?.lastEvent = .completed(shareId: state.shareId)
        }
    }

    // MARK: - Private: C initiates bootstrap connection to B

    private func initiateShareBootstrap(shareId: String, bootstrapKey: Data,
                                        ddns: String, port: UInt16, otherName: String) {
        Task {
            // Generate our 24KB message key to send to B
            let myKey = FreedomCrypto.generateMessageKey()
            let myKeyB64 = myKey.base64EncodedString()

            // Gather our info
            let defaults = UserDefaults.standard
            let myName = defaults.string(forKey: "my_name") ?? ""
            let myDdns = defaults.string(forKey: "my_domains") ?? ""
            let myPorts = defaults.string(forKey: "my_ports") ?? ""
            let myInfo: [String: String] = ["name": myName, "ddns": myDdns, "ports": myPorts]

            // Create a temporary ContactData to use ConnectionEngine's bootstrap
            let tempContact = ContactData(name: otherName, ddnsNames: ddns, ports: String(port))
            let engine = ConnectionEngine(contact: tempContact)

            let success = await engine.bootstrapSendKey(
                ddns: ddns,
                port: port,
                bootstrapKey: bootstrapKey,
                myKey: myKey,
                myInfo: myInfo
            )

            if success {
                // Save the new contact with our send key
                let encSendKey = PasskeySession.shared.encryptField(myKeyB64) ?? myKeyB64
                var newContact = ContactData(name: otherName, ddnsNames: ddns, ports: String(port))
                newContact.sendKey0 = encSendKey
                newContact.sendKeyCreatedAt0 = Int64(Date().timeIntervalSince1970 * 1000)
                newContact.activeSendKeyIdx = 0

                if let saved = try? self.db.insertContact(newContact) {
                    self.logger.info("Share bootstrap to \(otherName) succeeded, contact saved (id=\(saved.id ?? -1))")
                    BootstrapKeyHolder.shared.onHandshakeComplete?(saved)
                }
            } else {
                self.logger.error("Share bootstrap to \(ddns):\(port) failed")
                DispatchQueue.main.async { [weak self] in
                    self?.lastEvent = .failed(shareId: shareId, reason: "Bootstrap connection to \(otherName) failed")
                }
            }
        }
    }

    // MARK: - Cleanup timer for expired shares

    private func startCleanupTimer() {
        cleanupTimer = Timer.scheduledTimer(withTimeInterval: 60, repeats: true) { [weak self] _ in
            self?.cleanupExpiredShares()
        }
    }

    private func cleanupExpiredShares() {
        lock.lock()
        let now = Date()
        let expiredIds = pendingShares.filter { now.timeIntervalSince($0.value.createdAt) > Self.SHARE_TIMEOUT }.map(\.key)
        for id in expiredIds {
            pendingShares.removeValue(forKey: id)
            logger.info("Expired share \(id)")
        }

        incomingRequests.removeAll { _ in
            // Incoming requests don't track createdAt individually, but the share timeout
            // is enforced on A's side. If B/C hasn't acted, A will time out.
            false
        }
        lock.unlock()
    }
}
