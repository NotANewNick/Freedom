// ═════════════════════════════════════════════════════════════════════════════
//  MessageParser — Router dispatcher for message parsing
// ═════════════════════════════════════════════════════════════════════════════

import Foundation

enum MessageParser {

    /// Detects the message type from the raw string and delegates to the appropriate parser.
    static func parse(_ raw: String, senderAddress: String? = nil) -> ParsedMessage? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }

        if trimmed == "PING" {
            return ParsedMessage(type: .PING, content: "", senderAddress: senderAddress)
        }
        if trimmed == "PONG" {
            return ParsedMessage(type: .PONG, content: "", senderAddress: senderAddress)
        }
        if trimmed.hasPrefix("SRCH:") {
            return SearchMessageParser.parse(trimmed, senderAddress: senderAddress)
        }
        if trimmed.hasPrefix("INFRA:") {
            return InfraMessageParser.parse(trimmed, senderAddress: senderAddress)
        }
        return TextMessageParser.parse(trimmed, senderAddress: senderAddress)
    }
}
