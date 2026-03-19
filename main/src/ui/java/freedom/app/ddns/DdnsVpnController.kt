package freedom.app.ddns

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Reacts to VPN connection state changes by keeping DDNS records accurate.
 *
 * - [onVpnConnected]: fetch external IP; only push to DDNS services if IP changed
 *   since the last update.  This handles both initial connect and reconnects.
 * - [onVpnDisconnected]: set all DDNS records to [DEREGISTER_IP] (0.0.0.0), a
 *   non-routable address that stops inbound connection attempts.  The stored IP
 *   is also cleared so the next connect always re-evaluates.
 */
object DdnsVpnController {

    private const val TAG = "DdnsVpnController"

    /** Written to all DDNS services when VPN is down — does not route anywhere. */
    const val DEREGISTER_IP = "0.0.0.0"

    fun onVpnConnected(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val configs = DdnsConfigStorage.load(context)
                if (configs.isEmpty()) return@runCatching

                val newIp = DdnsUpdater.fetchPublicIp()
                val lastIp = DdnsConfigStorage.getLastIp(context)

                if (newIp == lastIp) {
                    Log.d(TAG, "VPN up, IP unchanged ($newIp) — no DDNS update needed")
                    return@runCatching
                }

                Log.i(TAG, "VPN up, IP $lastIp → $newIp — updating ${configs.size} DDNS service(s)")
                configs.forEach { config ->
                    runCatching { DdnsUpdater.update(config, newIp) }
                        .onFailure { Log.w(TAG, "Update failed for ${config.serviceType}: ${it.message}") }
                }
                DdnsConfigStorage.saveLastIp(context, newIp)
            }.onFailure { Log.e(TAG, "onVpnConnected error: ${it.message}") }
        }
    }

    fun onVpnDisconnected(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val configs = DdnsConfigStorage.load(context)
                if (configs.isEmpty()) return@runCatching

                Log.i(TAG, "VPN down — de-registering ${configs.size} DDNS service(s) to $DEREGISTER_IP")
                configs.forEach { config ->
                    runCatching { DdnsUpdater.update(config, DEREGISTER_IP) }
                        .onFailure { Log.w(TAG, "De-register failed for ${config.serviceType}: ${it.message}") }
                }
                // Clear stored IP so the next VPN connect always re-fetches and pushes
                DdnsConfigStorage.saveLastIp(context, "")
            }.onFailure { Log.e(TAG, "onVpnDisconnected error: ${it.message}") }
        }
    }
}
