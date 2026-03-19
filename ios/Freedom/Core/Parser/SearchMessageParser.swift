// ═════════════════════════════════════════════════════════════════════════════
//  SearchMessageParser — Search / discovery messages
// ═════════════════════════════════════════════════════════════════════════════

import Foundation

enum SearchMessageParser {

    static func parse(_ raw: String, senderAddress: String? = nil) -> ParsedMessage? {
        let body = String(raw.dropFirst(5)) // drop "SRCH:"

        if body == "REQ" {
            return ParsedMessage(type: .SEARCH_REQUEST, content: "", senderAddress: senderAddress)
        }
        if body.hasPrefix("RESP:") {
            return ParsedMessage(type: .SEARCH_RESPONSE, content: String(body.dropFirst(5)), senderAddress: senderAddress)
        }

        return nil
    }
}
