// =============================================================================
//  FileChaCha20 -- ChaCha20-Poly1305 file-chunk encryption
// =============================================================================
//
//  A fresh random 32-byte key is generated per file transfer and sent to the
//  receiver inside the FILE_START message, which itself travels over the
//  already-encrypted OTP message channel.
//
//  Nonce derivation is deterministic:
//      nonce = SHA-256(fileId_utf8 || chunkIdx_4byte_bigendian)[0:12]
//
//  Wire format produced by the caller:
//      FILE_START  -->  INFRA:FILE_START:{fileId}:{totalBytes}:{sha256}:{totalChunks}:{hexKey64}:{filename}
//      FCHUNK      -->  FCHUNK:{fileId}:{chunkIdx}/{totalChunks}:{base64_ciphertext_with_tag}
//
// =============================================================================

import Foundation
import CryptoKit

/// ChaCha20-Poly1305 file encryption helpers (stateless, no files on disk).
enum FileChaCha20 {

    /// Key length in bytes (256-bit).
    static let keyBytes = 32

    /// Generate a cryptographically random 32-byte key.
    static func generateKey() -> Data {
        var bytes = Data(count: keyBytes)
        bytes.withUnsafeMutableBytes { ptr in
            _ = SecRandomCopyBytes(kSecRandomDefault, keyBytes, ptr.baseAddress!)
        }
        return bytes
    }

    /// Deterministic 12-byte nonce derived from fileId + chunkIdx.
    ///
    /// `SHA-256(fileId_utf8 || chunkIdx_4byte_bigendian)[0:12]`
    static func deriveNonce(fileId: String, chunkIdx: Int) -> Data {
        var hasher = SHA256()
        hasher.update(data: Data(fileId.utf8))
        var idx = UInt32(chunkIdx).bigEndian
        hasher.update(data: Data(bytes: &idx, count: 4))
        let hash = hasher.finalize()
        return Data(hash.prefix(12))
    }

    /// Encrypt a plaintext chunk. Returns `ciphertext || tag` (16-byte Poly1305 tag appended).
    static func encrypt(plaintext: Data, key: Data, fileId: String, chunkIdx: Int) -> Data? {
        guard key.count == keyBytes else { return nil }
        let nonce = deriveNonce(fileId: fileId, chunkIdx: chunkIdx)
        do {
            let symmetricKey = SymmetricKey(data: key)
            let chaNonce = try ChaChaPoly.Nonce(data: nonce)
            let sealed = try ChaChaPoly.seal(plaintext, using: symmetricKey, nonce: chaNonce)
            return sealed.ciphertext + sealed.tag
        } catch {
            return nil
        }
    }

    /// Decrypt `ciphertext || tag`. Returns the original plaintext or nil on auth failure.
    static func decrypt(cipherWithTag: Data, key: Data, fileId: String, chunkIdx: Int) -> Data? {
        guard key.count == keyBytes, cipherWithTag.count >= 16 else { return nil }
        let nonce = deriveNonce(fileId: fileId, chunkIdx: chunkIdx)
        do {
            let symmetricKey = SymmetricKey(data: key)
            let chaNonce = try ChaChaPoly.Nonce(data: nonce)
            let ciphertext = cipherWithTag.prefix(cipherWithTag.count - 16)
            let tag = cipherWithTag.suffix(16)
            let sealedBox = try ChaChaPoly.SealedBox(nonce: chaNonce, ciphertext: ciphertext, tag: tag)
            return try ChaChaPoly.open(sealedBox, using: symmetricKey)
        } catch {
            return nil
        }
    }
}
