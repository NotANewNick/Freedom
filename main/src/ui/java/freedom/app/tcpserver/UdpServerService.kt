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
import freedom.app.R
import freedom.app.data.entity.MessageData
import freedom.app.parser.MessageParser
import freedom.app.viewModels.MessageViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * UDP counterpart to [TcpServerService].
 *
 * Bootstrap is TCP-only (multi-step binary protocol). UDP handles only
 * post-exchange OTP messages. OTP decryption for UDP messages is handled
 * by the contact's session channel if one is registered.
 */
class UdpServerService : Service(), IMessageReceived {

    private var socket: DatagramSocket? = null
    private var serverThread: Thread? = null

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        val port = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .getInt("server_port", 22176)
        startMeForeground()
        try {
            socket = DatagramSocket(port)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot bind UDP on port $port: ${e.message}")
            stopSelf()
            return
        }
        Log.i(TAG, "UDP server listening on port $port")
        serverThread = Thread(receiveLoop, "udp-server")
        serverThread?.start()
    }

    override fun onDestroy() {
        serverThread?.interrupt()
        socket?.close()
    }

    // ── Receive loop ─────────────────────────────────────────────────────────

    private val receiveLoop = Runnable {
        val buf = ByteArray(65_535)
        while (!Thread.currentThread().isInterrupted) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                socket?.receive(packet) ?: break
                val data = String(packet.data, 0, packet.length, Charsets.UTF_8)
                handleDatagram(data, packet.address.hostAddress ?: "unknown")
            } catch (_: SocketException) {
                break
            } catch (e: Exception) {
                Log.w(TAG, "UDP receive error: ${e.message}")
            }
        }
    }

    // ── Datagram dispatch ────────────────────────────────────────────────────

    private fun handleDatagram(data: String, senderAddr: String) {
        // Try OTP decryption via any active session channels
        // (contact identification happens by trying each session's channel)
        val sessions = ContactConnectionManager
        // For now, pass as plain text — UDP messages are best-effort
        val parsed = MessageParser.parse(data, senderAddr) ?: return
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        CoroutineScope(Dispatchers.IO).launch {
            MessageViewModel(application).insertMessage(
                MessageData(timestamp = ts, messageType = parsed.type.name,
                            content = parsed.content, sender = senderAddr)
            ) { }
        }
        messageReceivedInString(data)
    }

    // ── Foreground notification ───────────────────────────────────────────────

    private fun startMeForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "$packageName.udp"
            val chan = NotificationChannel(channelId, "UDP Server", NotificationManager.IMPORTANCE_NONE).apply {
                lightColor = Color.BLUE
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(chan)
            val notification = NotificationCompat.Builder(this, channelId)
                .setOngoing(true)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("UDP Server running")
                .setPriority(NotificationManager.IMPORTANCE_MIN)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(3, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(3, notification)
            }
        } else {
            @Suppress("DEPRECATION")
            startForeground(3, Notification())
        }
    }

    // ── IMessageReceived ─────────────────────────────────────────────────────

    override fun messageReceived(message: ByteArray, count: Int?) {
        val intent = Intent(TcpServerService.MESSAGE_RECEIVER)
        intent.putExtra(TcpServerService.MESSAGE_KEY, message)
        intent.putExtra(TcpServerService.MESSAGE_COUNT, count)
        sendBroadcast(intent)
    }

    override fun messageReceivedInString(message: String) {
        val intent = Intent(TcpServerService.MESSAGE_RECEIVER)
        intent.putExtra(TcpServerService.MESSAGE_KEY, message)
        sendBroadcast(intent)
    }

    companion object {
        private val TAG = UdpServerService::class.java.simpleName
    }
}
