package freedom.app.parser

object SearchMessageParser {

    // Wire format:
    //   Request:  "SRCH:REQ"
    //   Response: "SRCH:RESP:{json}"
    //   where json = {"searchable":true,"contacts":[{"name":"Alice","ref":"dGVzdA=="},...]}

    fun parse(raw: String, senderAddress: String?): ParsedMessage? {
        val body = raw.removePrefix("SRCH:")
        return when {
            body == "REQ" -> ParsedMessage(
                type          = MessageType.SEARCH_REQUEST,
                content       = "",
                senderAddress = senderAddress
            )
            body.startsWith("RESP:") -> ParsedMessage(
                type          = MessageType.SEARCH_RESPONSE,
                content       = body.removePrefix("RESP:"),
                senderAddress = senderAddress
            )
            else -> null
        }
    }
}
