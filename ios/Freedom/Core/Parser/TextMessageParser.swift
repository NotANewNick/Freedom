// ═════════════════════════════════════════════════════════════════════════════
//  TextMessageParser — Simple user text messages
// ═════════════════════════════════════════════════════════════════════════════

import Foundation

enum TextMessageParser {

    static func parse(_ raw: String, senderAddress: String? = nil) -> ParsedMessage? {
        var content = raw
        if content.hasPrefix("TEXT:") {
            content = String(content.dropFirst(5))
        }
        guard !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return ParsedMessage(type: .TEXT, content: content, senderAddress: senderAddress)
    }
}
