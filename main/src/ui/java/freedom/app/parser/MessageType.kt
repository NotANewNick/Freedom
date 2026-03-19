package freedom.app.parser

enum class MessageType {
    // ── User-visible ─────────────────────────────────────────────────────────
    TEXT,

    // ── Search / discovery (hidden from UI) ──────────────────────────────────
    SEARCH_REQUEST,          // SRCH:REQ
    SEARCH_RESPONSE,         // SRCH:RESP:{json}

    // ── Keepalive (hidden from UI) ────────────────────────────────────────────
    PING,                    // PING
    PONG,                    // PONG

    // ── Infrastructure refresh (hidden from UI) ───────────────────────────────
    INFRA_DDNS_UPDATE,       // INFRA:DDNS:{json}   — push new DDNS hostname
    INFRA_PORT_UPDATE,       // INFRA:PORT:{json}   — push new port number
    INFRA_ENDPOINT_ACK,      // INFRA:ACK           — endpoint update confirmed

    // ── Bootstrap key exchange (binary, handled at InputStream level) ────────
    BOOTSTRAP_KEY_CHUNK,     // 24KB key chunk during bootstrap
    BOOTSTRAP_KEY_DONE,      // all key chunks sent
    BOOTSTRAP_INFO,          // contact details (name, ddns, ports)
    BOOTSTRAP_ACK,           // acknowledgment

    // ── Key rotation (piggybacked on messages) ───────────────────────────────
    KEY_ROTATE_FLAG,         // "switching to new key next message"
    KEY_ROTATE_DELIVERY,     // "here is my new key"
    KEY_ROTATE_ACK,          // "received your new key, encrypted with it as proof"
    KEY_ROTATE_CONFIRM,      // "you may drop the old key"

    // ── File transfer control (hidden from UI) ────────────────────────────────
    INFRA_FILE_START,        // INFRA:FILE_START:{fileId}:{bytes}:{sha256}:{chunks}:{name}
    INFRA_FILE_ACK,          // INFRA:FILE_ACK:{fileId}   — receiver accepts transfer
    INFRA_FILE_DONE,         // INFRA:FILE_DONE:{fileId}:{sha256}  — all chunks sent
    INFRA_FILE_ERROR,        // INFRA:FILE_ERR:{fileId}:{reason}

    // ── File transfer results (visible in chat UI) ────────────────────────────
    FILE_RECEIVED,           // File successfully received and verified
    FILE_SENT,               // File successfully sent

    // ── Contact sharing (hidden from UI) ─────────────────────────────────────
    SHARE_REQUEST,           // INFRA:SHARE_REQ:{shareId}:{otherName}:{message}
    SHARE_APPROVE,           // INFRA:SHARE_APPROVE:{shareId}
    SHARE_DENY,              // INFRA:SHARE_DENY:{shareId}
    SHARE_CONNECT,           // INFRA:SHARE_CONNECT:{shareId}:{payload...}
    SHARE_FAIL,              // INFRA:SHARE_FAIL:{shareId}:{reason}
}
