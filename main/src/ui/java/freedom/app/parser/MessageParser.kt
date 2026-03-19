package freedom.app.parser

object MessageParser {

    /**
     * Detects the message type from the raw string and delegates to the appropriate parser.
     * Returns null if the message is empty or unrecognisable.
     */
    fun parse(raw: String, senderAddress: String? = null): ParsedMessage? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        return when {
            trimmed == "PING"             -> ParsedMessage(MessageType.PING,  "", senderAddress)
            trimmed == "PONG"             -> ParsedMessage(MessageType.PONG,  "", senderAddress)
            trimmed.startsWith("SRCH:")   -> SearchMessageParser.parse(trimmed, senderAddress)
            trimmed.startsWith("INFRA:")  -> InfraMessageParser.parse(trimmed, senderAddress)
            else                          -> TextMessageParser.parse(trimmed, senderAddress)
        }
    }
}
