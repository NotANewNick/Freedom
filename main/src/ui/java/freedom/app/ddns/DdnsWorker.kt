package freedom.app.ddns

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class DdnsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Never update DDNS unless the VPN tunnel is up
        val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val vpnActive = cm.allNetworks.any { network ->
            cm.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
        if (!vpnActive) return Result.success()

        val configs = DdnsConfigStorage.load(applicationContext)
        if (configs.isEmpty()) return Result.success()

        val ip = try {
            DdnsUpdater.fetchPublicIp()
        } catch (e: Exception) {
            return Result.retry()
        }

        // Only push to DDNS services if IP has changed since last update
        val lastIp = DdnsConfigStorage.getLastIp(applicationContext)
        if (ip == lastIp) return Result.success()

        configs.forEach { config ->
            runCatching { DdnsUpdater.update(config, ip) }
        }

        DdnsConfigStorage.saveLastIp(applicationContext, ip)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "ddns_ip_watch"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DdnsWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        }
    }
}
