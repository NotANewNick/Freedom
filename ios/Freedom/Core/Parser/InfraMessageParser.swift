// ═════════════════════════════════════════════════════════════════════════════
//  InfraMessageParser — Infrastructure / control messages
// ═════════════════════════════════════════════════════════════════════════════

import Foundation

enum InfraMessageParser {

    static func parse(_ raw: String, senderAddress: String? = nil) -> ParsedMessage? {
        let body = String(raw.dropFirst(6)) // drop "INFRA:"

        if body.hasPrefix("DDNS:") {
            return ParsedMessage(type: .INFRA_DDNS_UPDATE, content: String(body.dropFirst(5)), senderAddress: senderAddress)
        }
        if body.hasPrefix("PORT:") {
            return ParsedMessage(type: .INFRA_PORT_UPDATE, content: String(body.dropFirst(5)), senderAddress: senderAddress)
        }
        if body == "ACK" {
            return ParsedMessage(type: .INFRA_ENDPOINT_ACK, content: "", senderAddress: senderAddress)
        }

        // Key rotation
        if body == "KR_FLAG" {
            return ParsedMessage(type: .KEY_ROTATE_FLAG, content: "", senderAddress: senderAddress)
        }
        if body.hasPrefix("KR_KEY:") {
            return ParsedMessage(type: .KEY_ROTATE_DELIVERY, content: String(body.dropFirst(7)), senderAddress: senderAddress)
        }
        if body == "KR_ACK" {
            return ParsedMessage(type: .KEY_ROTATE_ACK, content: "", senderAddress: senderAddress)
        }
        if body == "KR_OK" {
            return ParsedMessage(type: .KEY_ROTATE_CONFIRM, content: "", senderAddress: senderAddress)
        }

        // File transfers
        if body.hasPrefix("FILE_START:") {
            return ParsedMessage(type: .INFRA_FILE_START, content: String(body.dropFirst(11)), senderAddress: senderAddress)
        }
        if body.hasPrefix("FILE_ACK:") {
            return ParsedMessage(type: .INFRA_FILE_ACK, content: String(body.dropFirst(9)), senderAddress: senderAddress)
        }
        if body.hasPrefix("FILE_DONE:") {
            return ParsedMessage(type: .INFRA_FILE_DONE, content: String(body.dropFirst(10)), senderAddress: senderAddress)
        }
        if body.hasPrefix("FILE_ERR:") {
            return ParsedMessage(type: .INFRA_FILE_ERROR, content: String(body.dropFirst(9)), senderAddress: senderAddress)
        }

        // Contact sharing
        if body.hasPrefix("SHARE_REQ:") {
            return ParsedMessage(type: .SHARE_REQ, content: String(body.dropFirst(10)), senderAddress: senderAddress)
        }
        if body.hasPrefix("SHARE_APPROVE:") {
            return ParsedMessage(type: .SHARE_APPROVE, content: String(body.dropFirst(14)), senderAddress: senderAddress)
        }
        if body.hasPrefix("SHARE_DENY:") {
            return ParsedMessage(type: .SHARE_DENY, content: String(body.dropFirst(11)), senderAddress: senderAddress)
        }
        if body.hasPrefix("SHARE_CONNECT:") {
            return ParsedMessage(type: .SHARE_CONNECT, content: String(body.dropFirst(14)), senderAddress: senderAddress)
        }
        if body.hasPrefix("SHARE_FAIL:") {
            return ParsedMessage(type: .SHARE_FAIL, content: String(body.dropFirst(11)), senderAddress: senderAddress)
        }

        // Unknown INFRA — treat as text
        return ParsedMessage(type: .TEXT, content: raw, senderAddress: senderAddress)
    }
}
