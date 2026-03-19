// ═════════════════════════════════════════════════════════════════════════════
//  MessageType — Enum of all message types in the Freedom protocol
// ═════════════════════════════════════════════════════════════════════════════

import Foundation

enum MessageType: String, CaseIterable {
    // User-visible
    case TEXT

    // Search / discovery
    case SEARCH_REQUEST
    case SEARCH_RESPONSE

    // Keepalive
    case PING
    case PONG

    // Infrastructure
    case INFRA_DDNS_UPDATE
    case INFRA_PORT_UPDATE
    case INFRA_ENDPOINT_ACK

    // Bootstrap (binary)
    case BOOTSTRAP_KEY_CHUNK
    case BOOTSTRAP_KEY_DONE
    case BOOTSTRAP_INFO
    case BOOTSTRAP_ACK

    // Key rotation
    case KEY_ROTATE_FLAG
    case KEY_ROTATE_DELIVERY
    case KEY_ROTATE_ACK
    case KEY_ROTATE_CONFIRM

    // File transfer
    case INFRA_FILE_START
    case INFRA_FILE_ACK
    case INFRA_FILE_DONE
    case INFRA_FILE_ERROR
    case FILE_RECEIVED
    case FILE_SENT

    // Contact sharing
    case SHARE_REQ
    case SHARE_APPROVE
    case SHARE_DENY
    case SHARE_CONNECT
    case SHARE_FAIL
}
