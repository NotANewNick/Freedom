package freedom.app.ddns

enum class DdnsServiceType(
    val displayName: String,
    val field1Label: String,
    val field2Label: String,
    val field3Label: String?,
    val websiteUrl: String = ""
) {
    NOIP("No-IP", "Hostname", "Username", "Password", "https://www.noip.com"),
    DUCKDNS("DuckDNS", "Domain(s)", "Token", null, "https://www.duckdns.org"),
    DYNU("Dynu", "Hostname", "Username", "Password", "https://www.dynu.com"),
    CLOUDFLARE("Cloudflare", "API Token", "Zone ID", "Record ID", "https://www.cloudflare.com"),
    NAMECHEAP("Namecheap", "Host", "Domain", "DDNS Password", "https://www.namecheap.com"),
    DESEC("deSEC", "Hostname", "E-mail", "Password", "https://desec.io"),
    DYNV6("dynv6", "Hostname", "Token", null, "https://dynv6.com"),
    YDNS("YDNS", "Hostname", "E-mail", "Password", "https://ydns.io"),
    IPV64("IPv64", "Hostname", "API Key", null, "https://ipv64.net"),
    FREEDNS("FreeDNS", "Hostname", "Update Token", null, "https://freedns.afraid.org")
}
