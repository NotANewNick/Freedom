package freedom.app.parser

object InfraMessageParser {

    // Wire format after "INFRA:" prefix:
    //   DDNS:{json}     → INFRA_DDNS_UPDATE
    //   PORT:{json}     → INFRA_PORT_UPDATE
    //   ACK             → INFRA_ENDPOINT_ACK
    //   KR_FLAG         → KEY_ROTATE_FLAG
    //   KR_KEY:{b64}    → KEY_ROTATE_DELIVERY
    //   KR_ACK          → KEY_ROTATE_ACK
    //   KR_OK           → KEY_ROTATE_CONFIRM
    //   SHARE_REQ:{shareId}:{name}:{msg}    → SHARE_REQUEST
    //   SHARE_APPROVE:{shareId}             → SHARE_APPROVE
    //   SHARE_DENY:{shareId}                → SHARE_DENY
    //   SHARE_CONNECT:{shareId}:{payload}   → SHARE_CONNECT
    //   SHARE_FAIL:{shareId}:{reason}       → SHARE_FAIL

    fun parse(raw: String, senderAddress: String?): ParsedMessage? {
        val body = raw.removePrefix("INFRA:")
        return when {
            body.startsWith("DDNS:")     -> ParsedMessage(MessageType.INFRA_DDNS_UPDATE,    body.removePrefix("DDNS:"),     senderAddress)
            body.startsWith("PORT:")     -> ParsedMessage(MessageType.INFRA_PORT_UPDATE,    body.removePrefix("PORT:"),     senderAddress)
            body == "ACK"                -> ParsedMessage(MessageType.INFRA_ENDPOINT_ACK,   "",                             senderAddress)
            body == "KR_FLAG"            -> ParsedMessage(MessageType.KEY_ROTATE_FLAG,      "",                             senderAddress)
            body.startsWith("KR_KEY:")   -> ParsedMessage(MessageType.KEY_ROTATE_DELIVERY,  body.removePrefix("KR_KEY:"),   senderAddress)
            body == "KR_ACK"             -> ParsedMessage(MessageType.KEY_ROTATE_ACK,       "",                             senderAddress)
            body == "KR_OK"              -> ParsedMessage(MessageType.KEY_ROTATE_CONFIRM,   "",                             senderAddress)
            body.startsWith("FILE_START:") -> ParsedMessage(MessageType.INFRA_FILE_START, body.removePrefix("FILE_START:"), senderAddress)
            body.startsWith("FILE_ACK:")   -> ParsedMessage(MessageType.INFRA_FILE_ACK,   body.removePrefix("FILE_ACK:"),   senderAddress)
            body.startsWith("FILE_DONE:")  -> ParsedMessage(MessageType.INFRA_FILE_DONE,  body.removePrefix("FILE_DONE:"),  senderAddress)
            body.startsWith("FILE_ERR:")   -> ParsedMessage(MessageType.INFRA_FILE_ERROR, body.removePrefix("FILE_ERR:"),   senderAddress)
            body.startsWith("SHARE_REQ:")     -> ParsedMessage(MessageType.SHARE_REQUEST,  body.removePrefix("SHARE_REQ:"),     senderAddress)
            body.startsWith("SHARE_APPROVE:") -> ParsedMessage(MessageType.SHARE_APPROVE,  body.removePrefix("SHARE_APPROVE:"), senderAddress)
            body.startsWith("SHARE_DENY:")    -> ParsedMessage(MessageType.SHARE_DENY,     body.removePrefix("SHARE_DENY:"),    senderAddress)
            body.startsWith("SHARE_CONNECT:") -> ParsedMessage(MessageType.SHARE_CONNECT,  body.removePrefix("SHARE_CONNECT:"), senderAddress)
            body.startsWith("SHARE_FAIL:")    -> ParsedMessage(MessageType.SHARE_FAIL,     body.removePrefix("SHARE_FAIL:"),    senderAddress)
            else                           -> null
        }
    }
}
