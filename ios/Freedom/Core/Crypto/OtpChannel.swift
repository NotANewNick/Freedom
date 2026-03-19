// ═════════════════════════════════════════════════════════════════════════════
//  OtpChannel — Per-contact encrypt/decrypt with key rotation tracking
// ═════════════════════════════════════════════════════════════════════════════
//
//  Uses cyclic XOR: every message is XOR'd with the full key starting at byte 0.
//  The same key is reused for every message until rotation. This is NOT true OTP —
//  it trades perfect secrecy for simplicity (no offset tracking or state sync).
//
//  Tracks messagesSent to trigger key rotation at rotationThreshold.
//
//  Each direction has its own key:
//    sendKey — encrypts messages WE send
//    recvKey — decrypts messages WE receive
//
// ═════════════════════════════════════════════════════════════════════════════

import Foundation
import os.log

final class OtpChannel: @unchecked Sendable {

    let contactId: Int64
    private let sendKey: Data
    private let recvKey: Data
    private let rotationThreshold: Int
    private let lock = NSLock()

    /// Messages sent with the current send key (in this session).
    private(set) var messagesSent: Int = 0

    init(
        contactId: Int64,
        sendKey: Data,
        recvKey: Data,
        rotationThreshold: Int = FreedomCrypto.DEFAULT_ROTATION_THRESHOLD
    ) {
        self.contactId = contactId
        self.sendKey = sendKey
        self.recvKey = recvKey
        self.rotationThreshold = rotationThreshold
    }

    /// Encrypt plaintext and return a Base64 wire-ready string.
    /// Returns nil if the send key is empty.
    func encrypt(_ plaintext: String) -> String? {
        guard !sendKey.isEmpty else { return nil }
        do {
            let result = FreedomCrypto.encrypt(plaintext, keyBytes: sendKey)
            lock.lock()
            messagesSent += 1
            lock.unlock()
            return result
        }
    }

    /// Decrypt a Base64 wire line produced by the remote side.
    /// Returns nil if the recv key is empty or decryption fails.
    func decrypt(_ ciphertextBase64: String) -> String? {
        guard !recvKey.isEmpty else { return nil }
        let result = FreedomCrypto.decryptToString(ciphertextBase64, keyBytes: recvKey)
        return result.isEmpty ? nil : result
    }

    /// True when the send key should be rotated based on message count.
    func needsRotation() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return messagesSent >= rotationThreshold
    }
}
