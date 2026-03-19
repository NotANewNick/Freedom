// ═════════════════════════════════════════════════════════════════════════════
//  ParsedMessage — Result of message parsing
// ═════════════════════════════════════════════════════════════════════════════

import Foundation

struct ParsedMessage {
    let type: MessageType
    let content: String
    let senderAddress: String?
}
