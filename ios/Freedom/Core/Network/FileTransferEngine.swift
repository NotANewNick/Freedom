// =============================================================================
//  FileTransferEngine -- Chunked file transfer using ChaCha20-Poly1305
// =============================================================================
//
//  Wire format:
//    FILE_START  -->  INFRA:FILE_START:{fileId}:{totalBytes}:{sha256}:{totalChunks}:{hexKey64}:{filename}
//    FCHUNK      -->  FCHUNK:{fileId}:{chunkIdx}/{totalChunks}:{base64_ciphertext_with_tag}
//    Nonce       -->  SHA-256(fileId_utf8 + chunkIdx_4byte_bigendian)[0:12]
//
// =============================================================================

import Foundation
import CryptoKit
import os.log

final class FileTransferEngine {

    static let shared = FileTransferEngine()

    private let CHUNK_SIZE = 8 * 1024 // 8 KB
    private let logger = Logger(subsystem: "com.freedom", category: "FileTransferEngine")
    private let db = FreedomDatabase.shared

    // MARK: - In-progress receive state

    private struct ReceiveState {
        let fileId: String
        let filename: String
        let totalBytes: Int64
        let expectedSha: String
        let totalChunks: Int
        let chunkDir: URL
        let contactId: Int64
        let fileKey: Data
        var received: Set<Int> = []
    }

    private var receives: [String: ReceiveState] = [:]
    private let lock = NSLock()

    // MARK: - Send

    func sendFile(contactId: Int64, fileURL: URL, onProgress: @escaping (Float) -> Void) async -> Bool {
        do {
            let rawBytes = try Data(contentsOf: fileURL)
            guard !rawBytes.isEmpty else { return false }

            let fileKey = FileChaCha20.generateKey()
            let hexKey = fileKey.map { String(format: "%02x", $0) }.joined()

            let sha256 = sha256hex(rawBytes)
            let fileId = UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(16)
            let totalChunks = (rawBytes.count + CHUNK_SIZE - 1) / CHUNK_SIZE
            let filename = fileURL.lastPathComponent

            // Announce -- includes hex key for the receiver
            let announced = ContactConnectionManager.shared.send(
                contactId: contactId,
                plaintext: "INFRA:FILE_START:\(fileId):\(rawBytes.count):\(sha256):\(totalChunks):\(hexKey):\(filename)"
            )
            guard announced else { return false }

            // Send chunks
            for i in 0..<totalChunks {
                let start = i * CHUNK_SIZE
                let end = min(start + CHUNK_SIZE, rawBytes.count)
                let chunk = rawBytes.subdata(in: start..<end)

                guard let cipherWithTag = FileChaCha20.encrypt(
                    plaintext: chunk,
                    key: fileKey,
                    fileId: String(fileId),
                    chunkIdx: i
                ) else {
                    logger.error("[\(contactId)] ChaCha20 encrypt failed for chunk \(i)")
                    return false
                }

                let b64 = cipherWithTag.base64EncodedString()
                let line = "FCHUNK:\(fileId):\(i)/\(totalChunks):\(b64)"
                ContactConnectionManager.shared.sendRaw(contactId: contactId, line: line)
                onProgress(Float(i + 1) / Float(totalChunks))
            }

            ContactConnectionManager.shared.send(contactId: contactId,
                                                  plaintext: "INFRA:FILE_DONE:\(fileId):\(sha256)")

            // Record in chat
            let ts = Self.dateFormatter.string(from: Date())
            _ = try db.insertMessage(MessageData(
                timestamp: ts, messageType: "FILE_SENT", content: filename,
                sender: "me", contactId: contactId, direction: MessageData.SENT
            ))

            return true
        } catch {
            logger.error("sendFile failed: \(error.localizedDescription)")
            return false
        }
    }

    // MARK: - Receive: control messages

    func onFileStart(contactId: Int64, payload: String) {
        // payload = "{fileId}:{totalBytes}:{sha256}:{totalChunks}:{hexKey64}:{filename}"
        let parts = payload.split(separator: ":", maxSplits: 5).map(String.init)
        guard parts.count >= 6 else { return }

        let fileId = parts[0]
        let totalBytes = Int64(parts[1]) ?? 0
        let sha256 = parts[2]
        let totalChunks = Int(parts[3]) ?? 0
        let hexKey = parts[4]
        let filename = parts[5]

        guard hexKey.count == 64 else {
            logger.warning("[\(contactId)] FILE_START: invalid key length \(hexKey.count)")
            return
        }
        guard let fileKey = hexToData(hexKey), fileKey.count == FileChaCha20.keyBytes else {
            logger.warning("[\(contactId)] FILE_START: could not decode hex key")
            return
        }

        let cacheDir = FileManager.default.temporaryDirectory.appendingPathComponent("file_recv/\(fileId)")
        try? FileManager.default.createDirectory(at: cacheDir, withIntermediateDirectories: true)

        lock.lock()
        receives[fileId] = ReceiveState(
            fileId: fileId, filename: filename, totalBytes: totalBytes,
            expectedSha: sha256, totalChunks: totalChunks,
            chunkDir: cacheDir, contactId: contactId,
            fileKey: fileKey
        )
        lock.unlock()

        ContactConnectionManager.shared.send(contactId: contactId, plaintext: "INFRA:FILE_ACK:\(fileId)")
        logger.info("[\(contactId)] Receiving '\(filename)' (\(totalBytes) B, \(totalChunks) chunks)")
    }

    func onFileDone(contactId: Int64, contactName: String, payload: String) {
        guard let colonIdx = payload.firstIndex(of: ":") else { return }
        let fileId = String(payload[payload.startIndex..<colonIdx])
        let sha256 = String(payload[payload.index(after: colonIdx)...])

        lock.lock()
        guard let state = receives.removeValue(forKey: fileId) else { lock.unlock(); return }
        lock.unlock()

        guard state.received.count >= state.totalChunks else {
            logger.warning("[\(contactId)] FILE_DONE but only \(state.received.count)/\(state.totalChunks) chunks")
            return
        }

        // Reassemble
        let documentsDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        let outDir = documentsDir.appendingPathComponent("received_files")
        try? FileManager.default.createDirectory(at: outDir, withIntermediateDirectories: true)
        let outFile = outDir.appendingPathComponent(state.filename)

        do {
            var assembled = Data()
            for i in 0..<state.totalChunks {
                let chunkFile = state.chunkDir.appendingPathComponent("\(i)")
                assembled.append(try Data(contentsOf: chunkFile))
            }
            try assembled.write(to: outFile)
            try? FileManager.default.removeItem(at: state.chunkDir)

            // Verify
            let actualSha = sha256hex(assembled)
            guard actualSha == sha256 else {
                logger.warning("[\(contactId)] INTEGRITY CHECK FAILED for \(state.filename)")
                try? FileManager.default.removeItem(at: outFile)
                return
            }

            logger.info("[\(contactId)] '\(state.filename)' received and verified")

            let ts = Self.dateFormatter.string(from: Date())
            _ = try db.insertMessage(MessageData(
                timestamp: ts, messageType: "FILE_RECEIVED", content: state.filename,
                sender: contactName, contactId: contactId, direction: MessageData.RECEIVED
            ))
        } catch {
            logger.error("onFileDone failed: \(error.localizedDescription)")
        }
    }

    // MARK: - Receive: raw FCHUNK lines

    func handleChunk(contactId: Int64, rawLine: String) {
        // Format: FCHUNK:{fileId}:{chunkIdx}/{totalChunks}:{base64_ciphertext_with_tag}
        let body = String(rawLine.dropFirst("FCHUNK:".count))
        guard let c1 = body.firstIndex(of: ":") else { return }
        let fileId = String(body[body.startIndex..<c1])
        let rest1 = String(body[body.index(after: c1)...])
        guard let c2 = rest1.firstIndex(of: ":") else { return }
        let idxStr = String(rest1[rest1.startIndex..<c2])
        let b64 = String(rest1[rest1.index(after: c2)...])

        guard let chunkIdx = Int(idxStr.split(separator: "/").first ?? ""),
              let cipherWithTag = Data(base64Encoded: b64) else { return }

        lock.lock()
        guard let state = receives[fileId] else { lock.unlock(); return }
        lock.unlock()

        guard let plain = FileChaCha20.decrypt(
            cipherWithTag: cipherWithTag,
            key: state.fileKey,
            fileId: fileId,
            chunkIdx: chunkIdx
        ) else {
            logger.warning("[\(contactId)] ChaCha20 decrypt failed for chunk \(chunkIdx) of \(fileId)")
            return
        }

        let chunkFile = state.chunkDir.appendingPathComponent("\(chunkIdx)")
        try? plain.write(to: chunkFile)

        lock.lock()
        receives[fileId]?.received.insert(chunkIdx)
        lock.unlock()
    }

    // MARK: - Helpers

    private func sha256hex(_ data: Data) -> String {
        let hash = SHA256.hash(data: data)
        return hash.compactMap { String(format: "%02x", $0) }.joined()
    }

    /// Decode a hex string to Data (must have even length).
    private func hexToData(_ hex: String) -> Data? {
        let chars = Array(hex)
        guard chars.count % 2 == 0 else { return nil }
        var data = Data(capacity: chars.count / 2)
        for i in stride(from: 0, to: chars.count, by: 2) {
            guard let byte = UInt8(String(chars[i]) + String(chars[i + 1]), radix: 16) else { return nil }
            data.append(byte)
        }
        return data
    }

    private static let dateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd HH:mm:ss"
        f.locale = Locale(identifier: "en_US_POSIX")
        return f
    }()
}
