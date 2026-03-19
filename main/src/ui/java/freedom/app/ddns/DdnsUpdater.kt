package freedom.app.ddns

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object DdnsUpdater {

    private val client = OkHttpClient()

    fun fetchPublicIp(): String {
        val services = listOf(
            "https://api.ipify.org",
            "https://api4.my-ip.io/ip",
            "https://checkip.amazonaws.com",
            "https://icanhazip.com",
            "https://ipecho.net/plain"
        )
        val ipv4 = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")
        for (url in services) {
            runCatching {
                val ip = client.newCall(Request.Builder().url(url).build())
                    .execute().use { it.body?.string()?.trim() ?: "" }
                if (ip.matches(ipv4)) return ip
            }
        }
        error("Could not determine public IP from any service")
    }

    fun update(config: DdnsConfig, ip: String): String = when (config.serviceType) {
        DdnsServiceType.NOIP       -> updateNoIp(config.field1, config.field2, config.field3, ip)
        DdnsServiceType.DUCKDNS    -> updateDuckDns(config.field1, config.field2, ip)
        DdnsServiceType.DYNU       -> updateDynu(config.field1, config.field2, config.field3, ip)
        DdnsServiceType.CLOUDFLARE -> updateCloudflare(config.field1, config.field2, config.field3, ip)
        DdnsServiceType.NAMECHEAP  -> updateNamecheap(config.field1, config.field2, config.field3, ip)
        DdnsServiceType.DESEC      -> updateDeSec(config.field1, config.field2, config.field3, ip)
        DdnsServiceType.DYNV6      -> updateDynv6(config.field1, config.field2, ip)
        DdnsServiceType.YDNS       -> updateYdns(config.field1, config.field2, config.field3, ip)
        DdnsServiceType.IPV64      -> updateIpv64(config.field1, config.field2, ip)
        DdnsServiceType.FREEDNS    -> updateFreeDns(config.field2, ip)
    }

    private fun updateNoIp(hostname: String, username: String, password: String, ip: String): String {
        val creds = Base64.encodeToString("$username:$password".toByteArray(), Base64.NO_WRAP)
        val req = Request.Builder()
            .url("https://dynupdate.no-ip.com/nic/update?hostname=$hostname&myip=$ip")
            .header("Authorization", "Basic $creds")
            .header("User-Agent", "freedom.app/1.0 contact@example.com")
            .build()
        client.newCall(req).execute().use { return it.body?.string() ?: "No response" }
    }

    private fun updateDuckDns(domains: String, token: String, ip: String): String {
        val req = Request.Builder()
            .url("https://www.duckdns.org/update?domains=$domains&token=$token&ip=$ip")
            .build()
        client.newCall(req).execute().use { return it.body?.string() ?: "No response" }
    }

    private fun updateDynu(hostname: String, username: String, password: String, ip: String): String {
        val creds = Base64.encodeToString("$username:$password".toByteArray(), Base64.NO_WRAP)
        val req = Request.Builder()
            .url("https://api.dynu.com/nic/update?hostname=$hostname&myip=$ip")
            .header("Authorization", "Basic $creds")
            .build()
        client.newCall(req).execute().use { return it.body?.string() ?: "No response" }
    }

    private fun updateCloudflare(apiToken: String, zoneId: String, recordId: String, ip: String): String {
        val body = """{"content":"$ip"}""".toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("https://api.cloudflare.com/client/v4/zones/$zoneId/dns_records/$recordId")
            .header("Authorization", "Bearer $apiToken")
            .patch(body)
            .build()
        client.newCall(req).execute().use { return it.body?.string() ?: "No response" }
    }

    private fun updateNamecheap(host: String, domain: String, ddnsPassword: String, ip: String): String {
        val req = Request.Builder()
            .url("https://dynamicdns.park-your-domain.com/update?host=$host&domain=$domain&password=$ddnsPassword&ip=$ip")
            .build()
        client.newCall(req).execute().use { return it.body?.string() ?: "No response" }
    }

    // field1=hostname, field2=email, field3=password
    private fun updateDeSec(hostname: String, email: String, password: String, ip: String): String {
        val creds = Base64.encodeToString("$email:$password".toByteArray(), Base64.NO_WRAP)
        val req = Request.Builder()
            .url("https://update.dedyn.io/?hostname=$hostname&myip=$ip")
            .header("Authorization", "Basic $creds")
            .build()
        client.newCall(req).execute().use { return it.body?.string() ?: "No response" }
    }

    // field1=hostname (zone), field2=token
    private fun updateDynv6(hostname: String, token: String, ip: String): String {
        val req = Request.Builder()
            .url("https://dynv6.com/api/update?ipv4=$ip&token=$token&zone=$hostname")
            .build()
        client.newCall(req).execute().use { return it.body?.string() ?: "No response" }
    }

    // field1=hostname, field2=email, field3=password
    private fun updateYdns(hostname: String, email: String, password: String, ip: String): String {
        val creds = Base64.encodeToString("$email:$password".toByteArray(), Base64.NO_WRAP)
        val req = Request.Builder()
            .url("https://ydns.io/api/v1/update/?host=$hostname&ip=$ip")
            .header("Authorization", "Basic $creds")
            .build()
        client.newCall(req).execute().use { return it.body?.string() ?: "No response" }
    }

    // field1=hostname, field2=apiKey
    private fun updateIpv64(hostname: String, apiKey: String, ip: String): String {
        val req = Request.Builder()
            .url("https://ipv64.net/nic/update?key=$apiKey&domain=$hostname")
            .build()
        client.newCall(req).execute().use { return it.body?.string() ?: "No response" }
    }

    // field2=updateToken (field1 is hostname, kept for reference only)
    private fun updateFreeDns(token: String, ip: String): String {
        val req = Request.Builder()
            .url("https://sync.afraid.org/u/$token/?address=$ip")
            .build()
        client.newCall(req).execute().use { return it.body?.string() ?: "No response" }
    }
}
