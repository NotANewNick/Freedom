package freedom.app.ddns

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Handles the one-time registration / verification step for each DDNS provider.
 *
 * For providers that support it (deSEC) a full account + domain is created via
 * their REST API.  For others the call verifies that the credentials are valid
 * before returning a ready-to-use [DdnsConfig].
 *
 * @param type     the DDNS service
 * @param cred1    primary credential: token / email / API key
 * @param cred2    secondary credential: password (empty string if not needed)
 * @param hostname desired subdomain *without* TLD suffix, or the full hostname
 */
object DdnsRegistrar {

    private val client = OkHttpClient()
    private val JSON_TYPE = "application/json".toMediaType()

    suspend fun register(
        type: DdnsServiceType,
        cred1: String,
        cred2: String,
        hostname: String
    ): Result<DdnsConfig> = withContext(Dispatchers.IO) {
        runCatching {
            when (type) {
                DdnsServiceType.DUCKDNS   -> registerDuckDns(cred1, hostname)
                DdnsServiceType.DESEC     -> registerDeSec(cred1, cred2, hostname)
                DdnsServiceType.DYNV6     -> registerDynv6(cred1, hostname)
                DdnsServiceType.YDNS      -> registerYdns(cred1, cred2, hostname)
                DdnsServiceType.IPV64     -> registerIpv64(cred1, hostname)
                DdnsServiceType.FREEDNS   -> registerFreeDns(cred1, hostname)
                DdnsServiceType.NOIP      -> registerNoIp(cred1, cred2, hostname)
                DdnsServiceType.DYNU      -> registerDynu(cred1, cred2, hostname)
                DdnsServiceType.CLOUDFLARE ->
                    error("Cloudflare requires Zone ID and Record ID — use manual configuration")
                DdnsServiceType.NAMECHEAP  ->
                    error("Namecheap DDNS requires manual configuration")
            }
        }
    }

    // ── DuckDNS ───────────────────────────────────────────────────────────────
    // cred1=token, hostname=subdomain (without .duckdns.org)
    private fun registerDuckDns(token: String, subdomain: String): DdnsConfig {
        val sub = subdomain.removeSuffix(".duckdns.org")
        val req = Request.Builder()
            .url("https://www.duckdns.org/update?domains=$sub&token=$token&ip=")
            .build()
        val body = client.newCall(req).execute().use { it.body?.string() ?: "" }
        if (!body.startsWith("OK")) error("DuckDNS rejected credentials: $body")
        return DdnsConfig(serviceType = DdnsServiceType.DUCKDNS,
            field1 = sub, field2 = token, field3 = "")
    }

    // ── deSEC ─────────────────────────────────────────────────────────────────
    // cred1=email, cred2=password, hostname=subdomain (becomes hostname.dedyn.io)
    private fun registerDeSec(email: String, password: String, hostname: String): DdnsConfig {
        val full = if (hostname.endsWith(".dedyn.io")) hostname else "$hostname.dedyn.io"

        // Try account creation; 409 = account already exists, which is fine
        val acctBody = """{"email":"$email","password":"$password"}""".toRequestBody(JSON_TYPE)
        val acctCode = client.newCall(
            Request.Builder().url("https://desec.io/api/v1/auth/").post(acctBody).build()
        ).execute().use { it.code }
        if (acctCode != 200 && acctCode != 201 && acctCode != 409) {
            error("deSEC account step failed (HTTP $acctCode)")
        }

        // Login to get auth token
        val loginBody = """{"email":"$email","password":"$password"}""".toRequestBody(JSON_TYPE)
        val token = client.newCall(
            Request.Builder().url("https://desec.io/api/v1/auth/login/").post(loginBody).build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) error("deSEC login failed (HTTP ${resp.code})")
            JSONObject(resp.body?.string() ?: "{}").optString("token")
        }
        if (token.isEmpty()) error("deSEC: no auth token in login response")

        // Create domain; 409 = already exists, which is fine
        val domBody = """{"name":"$full"}""".toRequestBody(JSON_TYPE)
        val domCode = client.newCall(
            Request.Builder().url("https://desec.io/api/v1/domains/").post(domBody)
                .header("Authorization", "Token $token").build()
        ).execute().use { it.code }
        if (domCode != 201 && domCode != 409) error("deSEC domain creation failed (HTTP $domCode)")

        return DdnsConfig(serviceType = DdnsServiceType.DESEC,
            field1 = full, field2 = email, field3 = password)
    }

    // ── dynv6 ─────────────────────────────────────────────────────────────────
    // cred1=token, hostname=subdomain (becomes hostname.dynv6.net)
    private fun registerDynv6(token: String, hostname: String): DdnsConfig {
        val full = if (hostname.contains(".")) hostname else "$hostname.dynv6.net"
        val body = """{"name":"$full"}""".toRequestBody(JSON_TYPE)
        val code = client.newCall(
            Request.Builder().url("https://dynv6.com/api/v1/zones").post(body)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json").build()
        ).execute().use { it.code }
        if (code != 200 && code != 201 && code != 409) error("dynv6 zone creation failed (HTTP $code)")
        return DdnsConfig(serviceType = DdnsServiceType.DYNV6,
            field1 = full, field2 = token, field3 = "")
    }

    // ── YDNS ──────────────────────────────────────────────────────────────────
    // cred1=email, cred2=password, hostname=subdomain (becomes hostname.ydns.eu)
    private fun registerYdns(email: String, password: String, hostname: String): DdnsConfig {
        val full = if (hostname.contains(".")) hostname else "$hostname.ydns.eu"
        val creds = Base64.encodeToString("$email:$password".toByteArray(), Base64.NO_WRAP)
        val body = """{"host":"$full"}""".toRequestBody(JSON_TYPE)
        val code = client.newCall(
            Request.Builder().url("https://ydns.io/api/v1/hosts/").post(body)
                .header("Authorization", "Basic $creds").build()
        ).execute().use { it.code }
        if (code != 200 && code != 201 && code != 409) error("YDNS host creation failed (HTTP $code)")
        return DdnsConfig(serviceType = DdnsServiceType.YDNS,
            field1 = full, field2 = email, field3 = password)
    }

    // ── IPv64 ─────────────────────────────────────────────────────────────────
    // cred1=apiKey, hostname=subdomain (becomes subdomain.ipv64.net)
    private fun registerIpv64(apiKey: String, hostname: String): DdnsConfig {
        val sub = hostname.removeSuffix(".ipv64.net")
        val code = client.newCall(
            Request.Builder()
                .url("https://ipv64.net/nic/add?add_domain=$sub&shortdomain=ipv64.net")
                .header("Authorization", "Bearer $apiKey").build()
        ).execute().use { it.code }
        if (code != 200 && code != 201) error("IPv64 domain creation failed (HTTP $code)")
        return DdnsConfig(serviceType = DdnsServiceType.IPV64,
            field1 = "$sub.ipv64.net", field2 = apiKey, field3 = "")
    }

    // ── FreeDNS ───────────────────────────────────────────────────────────────
    // cred1=updateToken, hostname=full hostname (e.g. myhost.mooo.com)
    // Verifies by pinging the update URL; actual subdomain must be pre-created on website
    private fun registerFreeDns(token: String, hostname: String): DdnsConfig {
        val body = client.newCall(
            Request.Builder()
                .url("https://sync.afraid.org/u/$token/?address=127.0.0.1").build()
        ).execute().use { it.body?.string() ?: "" }
        if (body.isBlank() || body.contains("ERROR", ignoreCase = true)) {
            error("FreeDNS token appears invalid: $body")
        }
        return DdnsConfig(serviceType = DdnsServiceType.FREEDNS,
            field1 = hostname, field2 = token, field3 = "")
    }

    // ── No-IP ─────────────────────────────────────────────────────────────────
    // cred1=username, cred2=password, hostname=full hostname; verifies via DynDNS2 update
    private fun registerNoIp(username: String, password: String, hostname: String): DdnsConfig {
        val creds = Base64.encodeToString("$username:$password".toByteArray(), Base64.NO_WRAP)
        val body = client.newCall(
            Request.Builder()
                .url("https://dynupdate.no-ip.com/nic/update?hostname=$hostname&myip=127.0.0.1")
                .header("Authorization", "Basic $creds")
                .header("User-Agent", "freedom.app/1.0 contact@example.com").build()
        ).execute().use { it.body?.string() ?: "" }
        if (body.startsWith("nohost") || body.startsWith("badauth") || body.startsWith("!donator")) {
            error("No-IP error: $body")
        }
        return DdnsConfig(serviceType = DdnsServiceType.NOIP,
            field1 = hostname, field2 = username, field3 = password)
    }

    // ── Dynu ──────────────────────────────────────────────────────────────────
    // cred1=username, cred2=password, hostname=desired subdomain (full hostname or bare name)
    // Uses the Dynu REST API to authenticate and create the hostname
    private fun registerDynu(username: String, password: String, hostname: String): DdnsConfig {
        // Step 1: authenticate to get an OAuth2 token
        val md5Pass = java.security.MessageDigest.getInstance("MD5")
            .digest(password.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val authBody = """{"username":"$username","password":"$md5Pass"}""".toRequestBody(JSON_TYPE)
        val token = client.newCall(
            Request.Builder().url("https://api.dynu.com/v2/oauth2/token").post(authBody).build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) error("Dynu authentication failed (HTTP ${resp.code})")
            JSONObject(resp.body?.string() ?: "{}").optString("access_token")
        }
        if (token.isEmpty()) error("Dynu: no access token returned")

        // Step 2: create the hostname via REST API
        val full = if (hostname.contains(".")) hostname else "$hostname.freeddns.org"
        val createBody = """{"name":"$full","ipv4Address":"127.0.0.1"}""".toRequestBody(JSON_TYPE)
        val createCode = client.newCall(
            Request.Builder().url("https://api.dynu.com/v2/dns").post(createBody)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json").build()
        ).execute().use { it.code }
        if (createCode != 200 && createCode != 201 && createCode != 409) {
            error("Dynu hostname creation failed (HTTP $createCode)")
        }

        return DdnsConfig(serviceType = DdnsServiceType.DYNU,
            field1 = full, field2 = username, field3 = password)
    }
}
