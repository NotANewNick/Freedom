// ═════════════════════════════════════════════════════════════════════════════
//  PasskeySession — PBKDF2 + AES-256-GCM passkey protection
// ═════════════════════════════════════════════════════════════════════════════
//
//  Holds the PBKDF2-derived AES-256 key in memory for the lifetime of the app process.
//
//  - The passkey itself is NEVER stored. Only the PBKDF2 salt + a GCM verifier
//    blob are persisted to Keychain.
//  - The derived key is stored as Data (value type) so it can be
//    zeroed with lock() when the session should end.
//  - encryptField / decryptField protect individual ContactData columns
//    (keys) without encrypting the whole database.
//
// ═════════════════════════════════════════════════════════════════════════════

import Foundation
import CryptoKit
import CommonCrypto
import Security

final class PasskeySession {

    static let shared = PasskeySession()

    private static let VERIFY_PLAIN = "FREEDOM_PASSKEY_V1"

    private static let PBKDF2_ITER: UInt32 = 200_000
    private static let KEY_BITS     = 256
    private static let SALT_BYTES   = 32
    private static let GCM_IV_BYTES = 12

    /// Minimum number of characters required for a passkey.
    static let MIN_PASSKEY_LENGTH = 12

    private var keyBytes: Data?
    private let lock = NSLock()

    // Keychain keys
    private static let keychainService = "com.freedom.passkey"
    private static let keySalt = "passkey_salt"
    private static let keyVerifier = "passkey_verifier"

    var isUnlocked: Bool {
        lock.lock()
        defer { lock.unlock() }
        return keyBytes != nil
    }

    func isPasskeySet() -> Bool {
        return keychainRead(key: Self.keySalt) != nil
    }

    /// First-time setup. Generates a random salt, derives the AES key, stores
    /// salt + verifier, and unlocks the session.
    func setup(passkey: String) {
        var salt = Data(count: Self.SALT_BYTES)
        salt.withUnsafeMutableBytes { _ = SecRandomCopyBytes(kSecRandomDefault, Self.SALT_BYTES, $0.baseAddress!) }

        let derived = derive(passkey: passkey, salt: salt)
        let verifier = aesGcmEncrypt(data: Data(Self.VERIFY_PLAIN.utf8), key: derived)

        keychainWrite(key: Self.keySalt, data: salt)
        keychainWrite(key: Self.keyVerifier, data: verifier)

        lock.lock()
        keyBytes = derived
        lock.unlock()
    }

    /// Unlock on subsequent launches. Returns true if the passkey is correct.
    func unlock(passkey: String) -> Bool {
        guard let salt = keychainRead(key: Self.keySalt),
              let verifier = keychainRead(key: Self.keyVerifier) else {
            return false
        }

        let derived = derive(passkey: passkey, salt: salt)
        do {
            let plain = try aesGcmDecrypt(data: verifier, key: derived)
            if String(data: plain, encoding: .utf8) == Self.VERIFY_PLAIN {
                lock.lock()
                keyBytes = derived
                lock.unlock()
                return true
            } else {
                zeroData(&[derived])
                return false
            }
        } catch {
            var d = derived
            zeroData(&[d])
            return false
        }
    }

    /// Zero and discard the in-memory AES key.
    func lockSession() {
        lock.lock()
        if var k = keyBytes {
            k.resetBytes(in: 0..<k.count)
        }
        keyBytes = nil
        lock.unlock()
    }

    /// Encrypt a field value with the session key.
    /// Empty string is returned unchanged. Returns nil if session is locked.
    func encryptField(_ plaintext: String) -> String? {
        if plaintext.isEmpty { return "" }
        lock.lock()
        guard let k = keyBytes else { lock.unlock(); return nil }
        lock.unlock()
        let encrypted = aesGcmEncrypt(data: Data(plaintext.utf8), key: k)
        return encrypted.base64EncodedString()
    }

    /// Decrypt a field value.
    /// Empty string is returned unchanged. Returns nil if session is locked.
    /// If AES-GCM decryption fails (legacy plaintext), returns encoded unchanged.
    func decryptField(_ encoded: String) -> String? {
        if encoded.isEmpty { return "" }
        lock.lock()
        guard let k = keyBytes else { lock.unlock(); return nil }
        lock.unlock()
        do {
            guard let bytes = Data(base64Encoded: encoded) else { return encoded }
            let plain = try aesGcmDecrypt(data: bytes, key: k)
            return String(data: plain, encoding: .utf8) ?? encoded
        } catch {
            return encoded  // legacy plaintext fallback
        }
    }

    // MARK: - Crypto primitives

    private func derive(passkey: String, salt: Data) -> Data {
        var derivedKey = Data(count: Self.KEY_BITS / 8)
        let passcodeBytes = Array(passkey.utf8)
        derivedKey.withUnsafeMutableBytes { derivedPtr in
            salt.withUnsafeBytes { saltPtr in
                CCKeyDerivationPBKDF(
                    CCPBKDFAlgorithm(kCCPBKDF2),
                    passcodeBytes, passcodeBytes.count,
                    saltPtr.baseAddress!.assumingMemoryBound(to: UInt8.self), salt.count,
                    CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
                    Self.PBKDF2_ITER,
                    derivedPtr.baseAddress!.assumingMemoryBound(to: UInt8.self),
                    Self.KEY_BITS / 8
                )
            }
        }
        return derivedKey
    }

    private func aesGcmEncrypt(data: Data, key: Data) -> Data {
        let symmetricKey = SymmetricKey(data: key)
        let sealed = try! AES.GCM.seal(data, using: symmetricKey)
        // Format: nonce (12) + ciphertext + tag (16)
        return sealed.nonce.withUnsafeBytes { Data($0) } + sealed.ciphertext + sealed.tag
    }

    private func aesGcmDecrypt(data: Data, key: Data) throws -> Data {
        let iv = data.subdata(in: 0..<Self.GCM_IV_BYTES)
        let rest = data.subdata(in: Self.GCM_IV_BYTES..<data.count)
        let tagStart = rest.count - 16
        let ciphertext = rest.subdata(in: 0..<tagStart)
        let tag = rest.subdata(in: tagStart..<rest.count)

        let nonce = try AES.GCM.Nonce(data: iv)
        let sealedBox = try AES.GCM.SealedBox(nonce: nonce, ciphertext: ciphertext, tag: tag)
        let symmetricKey = SymmetricKey(data: key)
        return try AES.GCM.open(sealedBox, using: symmetricKey)
    }

    private func zeroData(_ datas: inout [Data]) {
        for i in 0..<datas.count {
            datas[i].resetBytes(in: 0..<datas[i].count)
        }
    }

    // MARK: - Keychain helpers

    private func keychainWrite(key: String, data: Data) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.keychainService,
            kSecAttrAccount as String: key
        ]
        SecItemDelete(query as CFDictionary)
        var add = query
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(add as CFDictionary, nil)
    }

    private func keychainRead(key: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.keychainService,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess else { return nil }
        return result as? Data
    }
}
