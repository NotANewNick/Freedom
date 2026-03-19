/*
 * Copyright (c) 2012-2025 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package freedom.app.tcpserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.TrafficStatsCompat
import freedom.app.R
import freedom.app.ddns.DdnsVpnMonitor
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

class TcpServerService : Service(), IMessageReceived {

    private var mServerSocket: ServerSocket? = null
    private var mInterface: IMessageReceived? = null
    var PORT = 22176

    private val runnable = Runnable {
        var socket: Socket? = null
        try {
            TrafficStatsCompat.clearThreadStatsTag()
            mServerSocket = ServerSocket(PORT)
            mInterface = this
            while (!Thread.currentThread().isInterrupted) {
                if (mServerSocket != null) {
                    socket = mServerSocket?.accept()
                    Log.i(TAG, "New client: $socket")

                    val senderAddress = socket?.remoteSocketAddress?.toString()
                    val t = TcpClientHandler(
                        application,
                        socket!!,
                        this,
                        senderAddress
                    )
                    t.start()
                } else {
                    Log.e(TAG, "Couldn't create ServerSocket!")
                }
            }
        } catch (e: SocketException) {
            e.printStackTrace()
            try {
                socket?.close()
            } catch (ex: IOException) {
                ex.printStackTrace()
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        PORT = getSharedPreferences("app_prefs", Context.MODE_PRIVATE).getInt("server_port", 22176)
        startMeForeground()
        Thread(runnable).start()
        DdnsVpnMonitor.register(this)
    }

    override fun onDestroy() {
        mServerSocket?.close()
        DdnsVpnMonitor.unregister(this)
    }

    private fun startMeForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = packageName
            val chan = NotificationChannel(
                channelId,
                "Tcp Server Background Service",
                NotificationManager.IMPORTANCE_NONE
            ).apply {
                lightColor = Color.BLUE
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(chan)

            val notification = NotificationCompat.Builder(this, channelId)
                .setOngoing(true)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Tcp Server is running in background")
                .setPriority(NotificationManager.IMPORTANCE_MIN)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(2, notification)
            }
        } else {
            startForeground(1, Notification())
        }
    }

    override fun messageReceived(message: ByteArray, count: Int?) {
        val intent = Intent(MESSAGE_RECEIVER)
        intent.putExtra(MESSAGE_KEY, message)
        intent.putExtra(MESSAGE_COUNT, count)
        sendBroadcast(intent)
    }

    override fun messageReceivedInString(message: String) {
        val intent = Intent(MESSAGE_RECEIVER)
        intent.putExtra(MESSAGE_KEY, message)
        sendBroadcast(intent)
    }

    companion object {
        private val TAG = TcpServerService::class.java.simpleName
        const val MESSAGE_RECEIVER = "MESSAGE_RECEIVER"
        const val MESSAGE_KEY = "MESSAGE_KEY"
        const val MESSAGE_COUNT = "MESSAGE_COUNT"
        const val CONN_COUNT = "CONN_COUNT"
        const val PORT_OPEN = "PORT_OPEN"
    }
}

interface IMessageReceived {
    fun messageReceived(message: ByteArray, count: Int?)
    fun messageReceivedInString(message: String)
}
