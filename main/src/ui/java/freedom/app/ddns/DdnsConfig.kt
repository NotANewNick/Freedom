package freedom.app.ddns

import java.util.UUID

data class DdnsConfig(
    val id: String = UUID.randomUUID().toString(),
    val serviceType: DdnsServiceType,
    val field1: String,
    val field2: String,
    val field3: String
) {
    fun summary(): String = field1
}

/** Returns the fully-qualified public hostname for use in DDNS / QR config. */
fun DdnsConfig.publicHostname(): String = when (serviceType) {
    DdnsServiceType.DUCKDNS -> {
        val sub = field1.split(",").first().trim()
        if (sub.contains(".")) sub else "$sub.duckdns.org"
    }
    DdnsServiceType.NAMECHEAP -> "$field1.$field2"
    else -> field1
}
