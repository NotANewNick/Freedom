// ═════════════════════════════════════════════════════════════════════════════
//  Compression — zlib DEFLATE wrapper
// ═════════════════════════════════════════════════════════════════════════════
//
//  The main compression is built into FreedomCrypto using the Compression
//  framework with COMPRESSION_ZLIB (raw DEFLATE, matching Android's
//  Deflater(BEST_COMPRESSION, nowrap=true)).
//
//  This file provides additional convenience wrappers if needed.
//
// ═════════════════════════════════════════════════════════════════════════════

import Foundation
import Compression

extension Data {

    /// Compress using raw DEFLATE (zlib, nowrap).
    func deflateCompressed() -> Data? {
        guard !isEmpty else { return nil }
        let destSize = count + 512
        var dest = Data(count: destSize)

        let compressedSize = withUnsafeBytes { srcPtr -> Int in
            dest.withUnsafeMutableBytes { dstPtr -> Int in
                compression_encode_buffer(
                    dstPtr.baseAddress!.assumingMemoryBound(to: UInt8.self), destSize,
                    srcPtr.baseAddress!.assumingMemoryBound(to: UInt8.self), count,
                    nil,
                    COMPRESSION_ZLIB
                )
            }
        }

        guard compressedSize > 0 else { return nil }
        return dest.prefix(compressedSize)
    }

    /// Decompress from raw DEFLATE (zlib, nowrap).
    func deflateDecompressed() -> Data? {
        guard !isEmpty else { return nil }
        var destSize = count * 4
        var dest = Data(count: destSize)

        while true {
            let decompressedSize = withUnsafeBytes { srcPtr -> Int in
                dest.withUnsafeMutableBytes { dstPtr -> Int in
                    compression_decode_buffer(
                        dstPtr.baseAddress!.assumingMemoryBound(to: UInt8.self), destSize,
                        srcPtr.baseAddress!.assumingMemoryBound(to: UInt8.self), count,
                        nil,
                        COMPRESSION_ZLIB
                    )
                }
            }

            if decompressedSize == 0 { return nil }
            if decompressedSize < destSize {
                return dest.prefix(decompressedSize)
            }
            destSize *= 2
            dest = Data(count: destSize)
        }
    }
}
