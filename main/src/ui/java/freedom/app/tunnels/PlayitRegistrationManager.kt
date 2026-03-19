package freedom.app.tunnels

import kotlinx.coroutines.delay

/**
 * Drives the playit.gg device-claim flow entirely through coroutines.
 *
 * Usage:
 *   val mgr = PlayitRegistrationManager()
 *   val code = mgr.generateCode()
 *   mgr.startSetup(code)          // fire-and-forget; begin polling
 *   val claimUrl = mgr.claimUrl(code)   // show this to the user
 *   val secret = mgr.awaitApproval(code)  // suspends until approved / rejected
 *   val (tunnelId, host, port) = mgr.createTunnel(secret, "My Tunnel")
 */
class PlayitRegistrationManager {

    private val api = PlayitApiService.create()

    /** Generate a random 10-hex-char claim code (client-side, no API call). */
    fun generateCode(): String {
        val bytes = ByteArray(5)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun claimUrl(code: String) = "https://playit.gg/claim/$code"

    /**
     * Start the setup handshake with the server (must be called before [awaitApproval]).
     * Returns the initial status string.
     */
    suspend fun startSetup(code: String): String {
        return api.claimSetup(ClaimSetupRequest(code)).status
    }

    /**
     * Poll until the user approves or rejects the claim in their browser.
     *
     * @return the secret_key on approval.
     * @throws IllegalStateException if the user rejected or the poll timed out.
     */
    suspend fun awaitApproval(code: String, timeoutMs: Long = 120_000L): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val resp = api.claimSetup(ClaimSetupRequest(code))
            when (resp.status) {
                "UserAccepted" -> {
                    val exchange = api.claimExchange(ClaimExchangeRequest(code))
                    return exchange.secretKey
                }
                "UserRejected" -> throw IllegalStateException("User rejected the claim")
                else -> delay(2_000L)
            }
        }
        throw IllegalStateException("Claim timed out after ${timeoutMs / 1000}s")
    }

    /**
     * Create a TCP tunnel and wait until playit.gg assigns a public address.
     *
     * @return Triple(tunnelId, assignedDomain, portStart)
     */
    suspend fun createAndWaitForTunnel(secretKey: String, name: String): Triple<String, String, Int> {
        val auth = "Agent-Key $secretKey"
        val created = api.createTunnel(auth, CreateTunnelRequest(name = name))
        val tunnelId = created.id

        // Poll until alloc.type == "allocated"
        val deadline = System.currentTimeMillis() + 60_000L
        while (System.currentTimeMillis() < deadline) {
            val list = api.listTunnels(auth)
            val entry = list.tunnels.firstOrNull { it.id == tunnelId }
            val alloc = entry?.alloc
            if (alloc?.type == "allocated" && alloc.assignedDomain != null && alloc.portStart != null) {
                return Triple(tunnelId, alloc.assignedDomain, alloc.portStart)
            }
            delay(3_000L)
        }
        throw IllegalStateException("Tunnel allocation timed out")
    }

    /** Delete a tunnel from playit.gg. */
    suspend fun deleteTunnel(secretKey: String, tunnelId: String) {
        api.deleteTunnel("Agent-Key $secretKey", DeleteTunnelRequest(tunnelId))
    }
}
