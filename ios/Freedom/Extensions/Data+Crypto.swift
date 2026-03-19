// ═════════════════════════════════════════════════════════════════════════════
//  Data+Crypto — XOR, Base64, hex helpers
// ═════════════════════════════════════════════════════════════════════════════

import Foundation

extension Data {

    /// Hex string representation of the data.
    var hexString: String {
        map { String(format: "%02x", $0) }.joined()
    }

    /// Initialize Data from a hex string.
    init?(hexString: String) {
        let len = hexString.count
        guard len % 2 == 0 else { return nil }
        var data = Data(capacity: len / 2)
        var index = hexString.startIndex
        for _ in 0..<(len / 2) {
            let nextIndex = hexString.index(index, offsetBy: 2)
            guard let byte = UInt8(hexString[index..<nextIndex], radix: 16) else { return nil }
            data.append(byte)
            index = nextIndex
        }
        self = data
    }

    /// XOR this data with another data (cyclic).
    func xored(with key: Data) -> Data {
        FreedomCrypto.xorCyclic(self, key: key)
    }

    /// Zero all bytes in this data.
    mutating func zeroOut() {
        resetBytes(in: 0..<count)
    }
}

extension String {
    /// Decode Base64 string to Data.
    var base64Decoded: Data? {
        Data(base64Encoded: self)
    }
}
