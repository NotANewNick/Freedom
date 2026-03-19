package freedom.app.tunnels

import com.google.gson.annotations.SerializedName
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

// ── Request / Response models ─────────────────────────────────────────────────

data class ClaimSetupRequest(
    @SerializedName("code")       val code: String,
    @SerializedName("agent_type") val agentType: String = "self-managed",
    @SerializedName("version")    val version: String  = "0.15.0"
)

data class ClaimSetupResponse(
    @SerializedName("status") val status: String   // WaitingForUserVisit | WaitingForUser | UserAccepted | UserRejected
)

data class ClaimExchangeRequest(
    @SerializedName("code") val code: String
)

data class ClaimExchangeResponse(
    @SerializedName("secret_key") val secretKey: String
)

data class CreateTunnelRequest(
    @SerializedName("name")    val name: String,
    @SerializedName("ports")   val ports: TunnelPorts   = TunnelPorts(),
    @SerializedName("origin")  val origin: TunnelOrigin = TunnelOrigin(),
    @SerializedName("enabled") val enabled: Boolean     = true
)

data class TunnelPorts(
    @SerializedName("type")  val type: String = "tcp",
    @SerializedName("count") val count: Int   = 1
)

data class TunnelOrigin(
    @SerializedName("type") val type: String = "agent"
)

data class CreateTunnelResponse(
    @SerializedName("id") val id: String
)

data class TunnelListResponse(
    @SerializedName("tunnels") val tunnels: List<TunnelEntry> = emptyList()
)

data class TunnelEntry(
    @SerializedName("id")    val id: String,
    @SerializedName("name")  val name: String,
    @SerializedName("alloc") val alloc: TunnelAlloc?
)

data class TunnelAlloc(
    @SerializedName("type")            val type: String,           // "pending" | "allocated"
    @SerializedName("assigned_domain") val assignedDomain: String? = null,
    @SerializedName("port_start")      val portStart: Int?        = null
)

data class DeleteTunnelRequest(
    @SerializedName("tunnel_id") val tunnelId: String
)

// ── Retrofit interface ────────────────────────────────────────────────────────

interface PlayitApiService {

    @POST("claim/setup")
    suspend fun claimSetup(@Body request: ClaimSetupRequest): ClaimSetupResponse

    @POST("claim/exchange")
    suspend fun claimExchange(@Body request: ClaimExchangeRequest): ClaimExchangeResponse

    @POST("v1/tunnels/create")
    suspend fun createTunnel(
        @Header("Authorization") auth: String,
        @Body request: CreateTunnelRequest
    ): CreateTunnelResponse

    @POST("v1/tunnels/list")
    suspend fun listTunnels(
        @Header("Authorization") auth: String,
        @Body body: Map<String, String> = emptyMap()
    ): TunnelListResponse

    @POST("tunnels/delete")
    suspend fun deleteTunnel(
        @Header("Authorization") auth: String,
        @Body request: DeleteTunnelRequest
    )

    companion object {
        private const val BASE_URL = "https://api.playit.gg/"

        fun create(): PlayitApiService = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PlayitApiService::class.java)
    }
}
