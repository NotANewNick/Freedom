package freedom.app.parser

object TextMessageParser {

    private const val PREFIX = "TEXT:"

    fun matches(raw: String): Boolean = raw.startsWith(PREFIX) || raw.isNotBlank()

    fun parse(raw: String, senderAddress: String? = null): ParsedMessage {
        val content = if (raw.startsWith(PREFIX)) raw.removePrefix(PREFIX).trim() else raw.trim()
        return ParsedMessage(MessageType.TEXT, content, senderAddress)
    }
}
