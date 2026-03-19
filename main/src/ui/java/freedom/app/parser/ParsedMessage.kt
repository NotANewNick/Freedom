package freedom.app.parser

data class ParsedMessage(
    val type: MessageType,
    val content: String,
    val senderAddress: String?
)