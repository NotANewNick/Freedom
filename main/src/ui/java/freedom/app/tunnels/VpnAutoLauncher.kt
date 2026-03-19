package freedom.app.tunnels

import android.content.Context
import android.util.Log
import freedom.app.core.ConfigParser
import freedom.app.core.ProfileManager
import freedom.app.core.VPNLaunchHelper
import freedom.app.data.entity.TunnelProfile
import java.io.File
import java.io.StringReader

/**
 * Automatically starts the OpenVPN connection for a [TunnelProfile] of
 * type [TunnelProfile.TYPE_OVPN].
 *
 * Port binding strategy
 * ─────────────────────
 * The OpenVPN API has no "port" parameter — port is embedded in the raw
 * config text as `remote <host> <port> [proto]`.  This object reads the
 * stored .ovpn file, patches that line with [TunnelProfile.publicHost] and
 * [TunnelProfile.publicPort], then launches the VPN engine directly via
 * [VPNLaunchHelper] (no AIDL service binding required).
 *
 * Only applicable to TYPE_OVPN profiles.  playit.gg and ngrok profiles
 * expose the local TCP server to the internet — their publicPort is the
 * remote contact's entry point, not a VPN server port.
 *
 * Call [connect] on the main thread (VPNLaunchHelper posts an Intent).
 */
object VpnAutoLauncher {

    private const val TAG = "VpnAutoLauncher"

    fun connect(context: Context, profile: TunnelProfile) {
        if (profile.type != TunnelProfile.TYPE_OVPN) {
            Log.w(TAG, "connect() called for non-OVPN type '${profile.type}' — skipped")
            return
        }
        try {
            val file = File(profile.ovpnPath)
            if (!file.exists()) {
                Log.e(TAG, "ovpn file not found at '${profile.ovpnPath}'")
                return
            }
            val raw = file.readText()
            val config = if (profile.publicHost.isNotEmpty() && profile.publicPort > 0) {
                patchRemote(raw, profile.publicHost, profile.publicPort)
            } else {
                raw
            }
            val cp = ConfigParser()
            cp.parseConfig(StringReader(config))
            val vpnProfile = cp.convertProfile()
            ProfileManager.setTemporaryProfile(context, vpnProfile)
            VPNLaunchHelper.startOpenVpn(
                vpnProfile, context,
                "auto-tunnel:${profile.name}",
                /* replace_running_vpn = */ true
            )
            Log.i(TAG, "VPN launched via '${profile.name}' → ${profile.publicHost}:${profile.publicPort}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN for tunnel '${profile.name}'", e)
        }
    }

    /**
     * Replaces every `remote …` directive with `remote <host> <port> <proto>`,
     * preserving the original protocol token when present, otherwise inferring
     * it from any top-level `proto tcp|udp` line.
     */
    private fun patchRemote(config: String, host: String, port: Int): String {
        val inferredProto = when {
            config.contains(Regex("(?i)proto\\s+tcp")) -> "tcp"
            else -> "udp"
        }
        return config.lines().joinToString("\n") { line ->
            val t = line.trimStart()
            if (t.startsWith("remote ", ignoreCase = true)) {
                val parts = t.split(Regex("\\s+"))
                // parts: [remote, host, port?, proto?]
                val proto = if (parts.size >= 4) parts[3] else inferredProto
                "remote $host $port $proto"
            } else {
                line
            }
        }
    }
}
