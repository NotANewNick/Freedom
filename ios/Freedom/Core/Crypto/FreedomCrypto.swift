// ═════════════════════════════════════════════════════════════════════════════
//  FreedomCrypto  —  single source of truth for all encryption in this app
// ═════════════════════════════════════════════════════════════════════════════
//
//  Pipeline (send):    plaintext  →  compress  →  flag byte  →  XOR-cyclic  →  Base64
//  Pipeline (receive): Base64     →  XOR-cyclic  →  flag byte  →  decompress  →  plaintext
//
//  Cyclic XOR model:  Every message is XOR'd with the full key starting at
//  byte 0.  The same key bytes are reused for every message until rotation.
//  No offset tracking, no state sync, no capacity limit (up to key size per message).
//
// ═════════════════════════════════════════════════════════════════════════════

import Foundation
import CryptoKit
import CommonCrypto
import Compression
import CoreMotion
import AVFoundation
import UIKit

enum FreedomCrypto {

    // MARK: - Constants

    /// Bootstrap key for QR exchange — 256 bytes keeps QR scannable at ERROR_CORRECT_L.
    static let BOOTSTRAP_KEY_BYTES = 256

    /// Per-direction message key — 24 KB allows messages up to 24 KB before compression.
    static let MESSAGE_KEY_BYTES = 24 * 1024

    /// Default messages before key rotation — balances security vs. UX interruption.
    static let DEFAULT_ROTATION_THRESHOLD = 100

    /// Number of segments produced from one generation session.
    static let KEY_SEGMENTS = 6

    /// Total bytes generated at once (144 KB), then split into KEY_SEGMENTS × MESSAGE_KEY_BYTES.
    static let MASTER_PAD_BYTES = MESSAGE_KEY_BYTES * KEY_SEGMENTS

    /// First 32 hex chars of key shown as visual fingerprint for manual verification.
    static let FINGERPRINT_LENGTH = 32

    // MARK: - Magic headers

    static let MAGIC_BOOTSTRAP: [UInt8]  = [0xFF, 0xFF, 0x42, 0x53]  // FF FF 42 53 ("BS")
    static let MAGIC_KEY_ROTATE: [UInt8] = [0xFF, 0xFF, 0x4B, 0x52]  // FF FF 4B 52 ("KR")

    // MARK: - Bootstrap packet types

    static let BS_KEY_CHUNK: UInt8 = 0x01
    static let BS_INFO:      UInt8 = 0x02
    static let BS_KEY_DONE:  UInt8 = 0x03
    static let BS_ACK:       UInt8 = 0x04

    // Internal flags stored as the first encrypted byte to signal compression
    private static let FLAG_UNCOMPRESSED: UInt8 = 0x00
    private static let FLAG_COMPRESSED:   UInt8 = 0x01

    // ══════════════════════════════════════════════════════════════════════════
    //  KEY GENERATION
    // ══════════════════════════════════════════════════════════════════════════

    /// Generate a bootstrap key (ephemeral, for QR).
    static func generateBootstrapKey() -> Data {
        var key = Data(count: BOOTSTRAP_KEY_BYTES)
        key.withUnsafeMutableBytes { ptr in
            _ = SecRandomCopyBytes(kSecRandomDefault, BOOTSTRAP_KEY_BYTES, ptr.baseAddress!)
        }
        return key
    }

    /// Generate a 24 KB per-direction message key. Returns raw bytes.
    static func generateMessageKey() -> Data {
        var key = Data(count: MESSAGE_KEY_BYTES)
        key.withUnsafeMutableBytes { ptr in
            _ = SecRandomCopyBytes(kSecRandomDefault, MESSAGE_KEY_BYTES, ptr.baseAddress!)
        }
        return key
    }

    /// Generate a new key pad with multi-source entropy.
    /// Returns a Base64-encoded string ready to store.
    static func generateKey(
        padLengthBytes: Int = MESSAGE_KEY_BYTES,
        extraEntropy: Data = Data()
    ) async -> String {
        precondition(padLengthBytes > 0, "padLengthBytes must be positive")
        var pad = Data(count: padLengthBytes)
        pad.withUnsafeMutableBytes { ptr in
            _ = SecRandomCopyBytes(kSecRandomDefault, padLengthBytes, ptr.baseAddress!)
        }

        xorInto(&pad, source: extraEntropy)

        // Collect sensor entropy
        let sensorBytes = await collectSensorBytes(durationMs: 200)
        xorInto(&pad, source: sensorBytes)

        xorInto(&pad, source: longToBytes(UInt64(ProcessInfo.processInfo.systemUptime * 1_000_000_000)))
        xorInto(&pad, source: longToBytes(mach_absolute_time()))
        xorInto(&pad, source: longToBytes(UInt64(Date().timeIntervalSince1970 * 1000)))
        xorInto(&pad, source: batteryBytes())
        xorInto(&pad, source: processBytes())

        return pad.base64EncodedString()
    }

    /// Collect raw sensor + microphone bytes over durationMs milliseconds.
    static func collectMotionEntropy(durationMs: Int = 3000) async -> Data {
        async let sensorTask = collectSensorBytes(durationMs: durationMs)
        async let micTask = collectMicrophoneBytes(durationMs: durationMs)
        let sensor = await sensorTask
        let mic = await micTask
        return sensor + mic
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  CYCLIC XOR CORE
    // ══════════════════════════════════════════════════════════════════════════

    /// XOR data with key starting at byte 0, cycling the key.
    /// Used for both bootstrap and message encryption.
    static func xorCyclic(_ data: Data, key: Data) -> Data {
        precondition(!key.isEmpty, "Key must not be empty")
        var result = Data(count: data.count)
        let keyBytes = [UInt8](key)
        let keyLen = keyBytes.count
        let dataBytes = [UInt8](data)
        for i in 0..<data.count {
            result[i] = dataBytes[i] ^ keyBytes[i % keyLen]
        }
        return result
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ENCRYPT  (compress → flag byte → cyclic XOR → Base64)
    // ══════════════════════════════════════════════════════════════════════════

    /// Compress plaintext, prepend a 1-byte compression flag, then XOR-cyclic
    /// with keyBytes. Returns a Base64 string.
    static func encrypt(_ plaintext: Data, keyBytes: Data) -> String {
        let compressed = compress(plaintext)
        let flag: UInt8
        let payload: Data
        if compressed.count < plaintext.count {
            flag = FLAG_COMPRESSED
            payload = compressed
        } else {
            flag = FLAG_UNCOMPRESSED
            payload = plaintext
        }

        var toEncrypt = Data([flag])
        toEncrypt.append(payload)
        let cipher = xorCyclic(toEncrypt, key: keyBytes)
        return cipher.base64EncodedString()
    }

    /// Convenience overload accepting a String plaintext.
    static func encrypt(_ plaintext: String, keyBytes: Data) -> String {
        encrypt(Data(plaintext.utf8), keyBytes: keyBytes)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  DECRYPT  (Base64 → cyclic XOR → strip flag → decompress)
    // ══════════════════════════════════════════════════════════════════════════

    /// Reverse of encrypt. Decrypt ciphertextBase64 using keyBytes,
    /// then decompress if the original was compressed.
    static func decrypt(_ ciphertextBase64: String, keyBytes: Data) -> Data {
        guard let cipherBytes = Data(base64Encoded: ciphertextBase64) else {
            return Data()
        }
        let decrypted = xorCyclic(cipherBytes, key: keyBytes)
        guard !decrypted.isEmpty else { return Data() }

        let flag = decrypted[0]
        let payload = decrypted.subdata(in: 1..<decrypted.count)

        switch flag {
        case FLAG_COMPRESSED:
            return decompress(payload)
        default:
            return payload
        }
    }

    /// Convenience overload returning a String.
    static func decryptToString(_ ciphertextBase64: String, keyBytes: Data) -> String {
        String(data: decrypt(ciphertextBase64, keyBytes: keyBytes), encoding: .utf8) ?? ""
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  UTILITY
    // ══════════════════════════════════════════════════════════════════════════

    /// First FINGERPRINT_LENGTH chars of the Base64 key — a short identifier for display.
    static func keyFingerprint(_ keyBase64: String) -> String {
        String(keyBase64.prefix(FINGERPRINT_LENGTH))
    }

    /// Split a master pad into segments equal Base64-encoded chunks.
    static func splitKey(_ masterKeyBase64: String, segments: Int = KEY_SEGMENTS) -> [String] {
        guard let bytes = Data(base64Encoded: masterKeyBase64) else { return [] }
        precondition(bytes.count % segments == 0,
                     "Master pad size \(bytes.count) B is not divisible by \(segments)")
        let chunkSize = bytes.count / segments
        return (0..<segments).map { i in
            bytes.subdata(in: i * chunkSize..<(i + 1) * chunkSize).base64EncodedString()
        }
    }

    /// Shannon entropy of the raw pad bytes, in bits per byte.
    static func entropyBitsPerByte(_ keyBase64: String) -> Double {
        guard let bytes = Data(base64Encoded: keyBase64), !bytes.isEmpty else { return 0.0 }
        var freq = [Int](repeating: 0, count: 256)
        for b in bytes { freq[Int(b)] += 1 }
        let n = Double(bytes.count)
        var h = 0.0
        for count in freq {
            guard count > 0 else { continue }
            let p = Double(count) / n
            h -= p * (log(p) / log(2.0))
        }
        return h
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  PASSCODE ENCRYPTION  (PBKDF2-SHA256 → AES-256-GCM)
    // ══════════════════════════════════════════════════════════════════════════

    /// PBKDF2 iterations — tuned for ~200 ms on mid-range devices (2024).
    private static let PBKDF2_ITERATIONS: UInt32 = 200_000
    private static let PBKDF2_KEY_BITS   = 256
    private static let SALT_BYTES        = 16
    private static let GCM_IV_BYTES      = 12
    private static let GCM_TAG_BITS      = 128

    static func encryptWithPasscode(_ plaintext: String, passcode: String) -> String {
        var salt = Data(count: SALT_BYTES)
        salt.withUnsafeMutableBytes { _ = SecRandomCopyBytes(kSecRandomDefault, SALT_BYTES, $0.baseAddress!) }
        var iv = Data(count: GCM_IV_BYTES)
        iv.withUnsafeMutableBytes { _ = SecRandomCopyBytes(kSecRandomDefault, GCM_IV_BYTES, $0.baseAddress!) }

        let key = deriveAesKey(passcode, salt: salt)
        let symmetricKey = SymmetricKey(data: key)
        let nonce = try! AES.GCM.Nonce(data: iv)
        let sealed = try! AES.GCM.seal(Data(plaintext.utf8), using: symmetricKey, nonce: nonce)
        // sealed.ciphertext + sealed.tag
        let ciphertext = sealed.ciphertext + sealed.tag
        return (salt + iv + ciphertext).base64EncodedString()
    }

    static func decryptWithPasscode(_ encoded: String, passcode: String) -> String {
        guard let data = Data(base64Encoded: encoded) else { return "" }
        let salt = data.subdata(in: 0..<SALT_BYTES)
        let iv = data.subdata(in: SALT_BYTES..<SALT_BYTES + GCM_IV_BYTES)
        let ciphertext = data.subdata(in: SALT_BYTES + GCM_IV_BYTES..<data.count)

        let key = deriveAesKey(passcode, salt: salt)
        let symmetricKey = SymmetricKey(data: key)
        let nonce = try! AES.GCM.Nonce(data: iv)
        // Last 16 bytes of ciphertext are the tag
        let tagStart = ciphertext.count - 16
        let ct = ciphertext.subdata(in: 0..<tagStart)
        let tag = ciphertext.subdata(in: tagStart..<ciphertext.count)
        let sealedBox = try! AES.GCM.SealedBox(nonce: nonce, ciphertext: ct, tag: tag)
        let plainData = try! AES.GCM.open(sealedBox, using: symmetricKey)
        return String(data: plainData, encoding: .utf8) ?? ""
    }

    private static func deriveAesKey(_ passcode: String, salt: Data) -> Data {
        var derivedKey = Data(count: PBKDF2_KEY_BITS / 8)
        let passcodeData = Array(passcode.utf8)
        derivedKey.withUnsafeMutableBytes { derivedPtr in
            salt.withUnsafeBytes { saltPtr in
                CCKeyDerivationPBKDF(
                    CCPBKDFAlgorithm(kCCPBKDF2),
                    passcodeData, passcodeData.count,
                    saltPtr.baseAddress!.assumingMemoryBound(to: UInt8.self), salt.count,
                    CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
                    PBKDF2_ITERATIONS,
                    derivedPtr.baseAddress!.assumingMemoryBound(to: UInt8.self),
                    PBKDF2_KEY_BITS / 8
                )
            }
        }
        return derivedKey
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  COMPRESSION  (raw DEFLATE, no GZIP header — nowrap=true)
    // ══════════════════════════════════════════════════════════════════════════

    private static func compress(_ data: Data) -> Data {
        guard !data.isEmpty else { return Data() }
        let sourceSize = data.count
        // Allocate worst-case buffer
        let destSize = sourceSize + 512
        var dest = Data(count: destSize)

        let compressedSize = data.withUnsafeBytes { srcPtr -> Int in
            dest.withUnsafeMutableBytes { dstPtr -> Int in
                compression_encode_buffer(
                    dstPtr.baseAddress!.assumingMemoryBound(to: UInt8.self), destSize,
                    srcPtr.baseAddress!.assumingMemoryBound(to: UInt8.self), sourceSize,
                    nil,
                    COMPRESSION_ZLIB
                )
            }
        }

        guard compressedSize > 0 else { return data }
        return dest.prefix(compressedSize)
    }

    private static func decompress(_ data: Data) -> Data {
        guard !data.isEmpty else { return Data() }
        // Start with 4x buffer, grow if needed
        var destSize = data.count * 4
        var dest = Data(count: destSize)

        while true {
            let decompressedSize = data.withUnsafeBytes { srcPtr -> Int in
                dest.withUnsafeMutableBytes { dstPtr -> Int in
                    compression_decode_buffer(
                        dstPtr.baseAddress!.assumingMemoryBound(to: UInt8.self), destSize,
                        srcPtr.baseAddress!.assumingMemoryBound(to: UInt8.self), data.count,
                        nil,
                        COMPRESSION_ZLIB
                    )
                }
            }

            if decompressedSize == 0 { return data }
            if decompressedSize < destSize {
                return dest.prefix(decompressedSize)
            }
            // Buffer was too small, double it
            destSize *= 2
            dest = Data(count: destSize)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ENTROPY SOURCES  (private — only called by generateKey)
    // ══════════════════════════════════════════════════════════════════════════

    private static func collectSensorBytes(durationMs: Int) async -> Data {
        var collected = Data()
        let motionManager = CMMotionManager()
        let interval = 1.0 / 100.0 // 100 Hz

        if motionManager.isDeviceMotionAvailable {
            motionManager.deviceMotionUpdateInterval = interval
            motionManager.startDeviceMotionUpdates()
        }
        if motionManager.isAccelerometerAvailable {
            motionManager.accelerometerUpdateInterval = interval
            motionManager.startAccelerometerUpdates()
        }
        if motionManager.isGyroAvailable {
            motionManager.gyroUpdateInterval = interval
            motionManager.startGyroUpdates()
        }
        if motionManager.isMagnetometerAvailable {
            motionManager.magnetometerUpdateInterval = interval
            motionManager.startMagnetometerUpdates()
        }

        try? await Task.sleep(nanoseconds: UInt64(durationMs) * 1_000_000)

        // Collect whatever sensor data is available
        if let dm = motionManager.deviceMotion {
            collected.append(floatBytes(Float(dm.attitude.roll)))
            collected.append(floatBytes(Float(dm.attitude.pitch)))
            collected.append(floatBytes(Float(dm.attitude.yaw)))
            collected.append(floatBytes(Float(dm.userAcceleration.x)))
            collected.append(floatBytes(Float(dm.userAcceleration.y)))
            collected.append(floatBytes(Float(dm.userAcceleration.z)))
            collected.append(floatBytes(Float(dm.rotationRate.x)))
            collected.append(floatBytes(Float(dm.rotationRate.y)))
            collected.append(floatBytes(Float(dm.rotationRate.z)))
            collected.append(floatBytes(Float(dm.gravity.x)))
            collected.append(floatBytes(Float(dm.gravity.y)))
            collected.append(floatBytes(Float(dm.gravity.z)))
            collected.append(longToBytes(UInt64(dm.timestamp * 1_000_000)))
        }
        if let accel = motionManager.accelerometerData {
            collected.append(floatBytes(Float(accel.acceleration.x)))
            collected.append(floatBytes(Float(accel.acceleration.y)))
            collected.append(floatBytes(Float(accel.acceleration.z)))
            collected.append(longToBytes(UInt64(accel.timestamp * 1_000_000)))
        }
        if let gyro = motionManager.gyroData {
            collected.append(floatBytes(Float(gyro.rotationRate.x)))
            collected.append(floatBytes(Float(gyro.rotationRate.y)))
            collected.append(floatBytes(Float(gyro.rotationRate.z)))
        }
        if let mag = motionManager.magnetometerData {
            collected.append(floatBytes(Float(mag.magneticField.x)))
            collected.append(floatBytes(Float(mag.magneticField.y)))
            collected.append(floatBytes(Float(mag.magneticField.z)))
        }

        motionManager.stopDeviceMotionUpdates()
        motionManager.stopAccelerometerUpdates()
        motionManager.stopGyroUpdates()
        motionManager.stopMagnetometerUpdates()

        return collected
    }

    private static func collectMicrophoneBytes(durationMs: Int) async -> Data {
        // Microphone entropy collection requires AVAudioEngine
        // This is a best-effort collection — if permissions not granted, returns empty
        let engine = AVAudioEngine()
        var collected = Data()

        do {
            let inputNode = engine.inputNode
            let format = inputNode.outputFormat(forBus: 0)
            let bufferSize = AVAudioFrameCount(format.sampleRate * Double(durationMs) / 1000.0)

            inputNode.installTap(onBus: 0, bufferSize: bufferSize, format: format) { buffer, _ in
                guard let channelData = buffer.floatChannelData?[0] else { return }
                let count = Int(buffer.frameLength)
                for i in 0..<min(count, 4096) {
                    var f = channelData[i]
                    collected.append(Data(bytes: &f, count: MemoryLayout<Float>.size))
                }
            }
            try engine.start()
            try await Task.sleep(nanoseconds: UInt64(durationMs) * 1_000_000)
            engine.stop()
            inputNode.removeTap(onBus: 0)
        } catch {
            // Microphone not available — return empty
        }

        return collected
    }

    private static func batteryBytes() -> Data {
        UIDevice.current.isBatteryMonitoringEnabled = true
        var data = Data()
        var level = UIDevice.current.batteryLevel
        data.append(Data(bytes: &level, count: MemoryLayout<Float>.size))
        var state = UIDevice.current.batteryState.rawValue
        data.append(Data(bytes: &state, count: MemoryLayout<Int>.size))
        return data
    }

    private static func processBytes() -> Data {
        var data = Data()
        data.append(longToBytes(UInt64(ProcessInfo.processInfo.processIdentifier)))
        data.append(longToBytes(mach_absolute_time()))
        data.append(longToBytes(UInt64(ProcessInfo.processInfo.physicalMemory)))
        data.append(longToBytes(UInt64(ProcessInfo.processInfo.systemUptime * 1_000_000)))
        return data
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  INTERNAL HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    /// XOR source bytes cyclically into target (wraps if source is shorter).
    private static func xorInto(_ target: inout Data, source: Data) {
        guard !source.isEmpty else { return }
        let sourceBytes = [UInt8](source)
        let sourceLen = sourceBytes.count
        target.withUnsafeMutableBytes { ptr in
            let bytes = ptr.baseAddress!.assumingMemoryBound(to: UInt8.self)
            for i in 0..<target.count {
                bytes[i] ^= sourceBytes[i % sourceLen]
            }
        }
    }

    static func longToBytes(_ v: UInt64) -> Data {
        var data = Data(count: 8)
        for i in 0..<8 {
            data[i] = UInt8((v >> (i * 8)) & 0xFF)
        }
        return data
    }

    private static func floatBytes(_ v: Float) -> Data {
        var f = v
        return Data(bytes: &f, count: MemoryLayout<Float>.size)
    }
}
