package freedom.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tunnel_profiles")
data class TunnelProfile(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,

    /** "playit" | "ngrok" | "ovpn" */
    @ColumnInfo(name = "type") val type: String,

    /** Display name shown in the list */
    @ColumnInfo(name = "name") val name: String,

    /** playit.gg: secret_key from claim exchange.  ngrok: authtoken. */
    @ColumnInfo(name = "secret_key") val secretKey: String = "",

    /** playit.gg: tunnel UUID from /v1/tunnels/create. ngrok: tunnel name. */
    @ColumnInfo(name = "tunnel_id") val tunnelId: String = "",

    /** The publicly reachable hostname assigned by the tunnel service. */
    @ColumnInfo(name = "public_host") val publicHost: String = "",

    /** The publicly reachable port assigned by the tunnel service. */
    @ColumnInfo(name = "public_port") val publicPort: Int = 0,

    /** OVPN type only: absolute path to the .ovpn file on device storage. */
    @ColumnInfo(name = "ovpn_path") val ovpnPath: String = "",

    /**
     * Lower number = higher priority.  The list is rendered in ascending order;
     * TcpOutgoingHandler tries addresses in this order.
     */
    @ColumnInfo(name = "priority") val priority: Int = 0,

    @ColumnInfo(name = "enabled") val enabled: Boolean = true,

    @ColumnInfo(name = "added_at") val addedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val TYPE_PLAYIT = "playit"
        const val TYPE_NGROK  = "ngrok"
        const val TYPE_OVPN   = "ovpn"
    }

    val publicAddress: String get() = if (publicHost.isNotEmpty() && publicPort > 0) "$publicHost:$publicPort" else ""
}
