package freedom.app.ddns

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

/**
 * Watches for VPN network events using [ConnectivityManager.NetworkCallback] and
 * delegates to [DdnsVpnController] on connect / disconnect.
 *
 * Register once from a long-lived component (e.g. [TcpServerService]).
 * The callback is scoped to the VPN transport only — unrelated network changes
 * (Wi-Fi, mobile data) are ignored.
 */
object DdnsVpnMonitor {

    private const val TAG = "DdnsVpnMonitor"

    private var vpnNetwork: Network? = null
    private var registered = false
    private lateinit var appContext: Context

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "VPN network available")
            vpnNetwork = network
            DdnsVpnController.onVpnConnected(appContext)
        }

        override fun onLost(network: Network) {
            if (network == vpnNetwork) {
                Log.d(TAG, "VPN network lost")
                vpnNetwork = null
                DdnsVpnController.onVpnDisconnected(appContext)
            }
        }
    }

    fun register(context: Context) {
        if (registered) return
        appContext = context.applicationContext
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // NET_CAPABILITY_NOT_VPN is included in the default builder — remove it so
        // the request actually matches VPN networks.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        cm.registerNetworkCallback(request, callback)
        registered = true
        Log.d(TAG, "VPN monitor registered")
    }

    fun unregister(context: Context) {
        if (!registered) return
        runCatching {
            (context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                .unregisterNetworkCallback(callback)
        }
        registered = false
        Log.d(TAG, "VPN monitor unregistered")
    }
}
