package moe.shizuku.manager.worker

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.receiver.ShizukuReceiverStarter
import moe.shizuku.manager.service.WatchdogManager
import rikka.shizuku.Shizuku

object AdbNetworkObserver {

    private const val TAG = "AdbNetworkObserver"

    @Volatile
    private var registered = false

    @Volatile
    private var lastTriggerMs = 0L
    private const val DEBOUNCE_MS = 5_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun register(app: Application) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (registered) return
        synchronized(this) {
            if (registered) return
            try {
                val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    .build()
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        onUnmeteredAvailable(app)
                    }
                }
                networkCallback = callback
                cm.registerNetworkCallback(request, callback)
                registered = true
                Log.d(TAG, "AdbNetworkObserver registered")
            } catch (e: Exception) {
                Log.w(TAG, "registerNetworkCallback failed", e)
            }
        }
    }

    private fun onUnmeteredAvailable(app: Application) {
        reArmWifiAfterSettle(app)

        if (Shizuku.pingBinder()) {
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(ShizukuReceiverStarter.NOTIFICATION_ID)
            return
        }

        if (WatchdogManager.isUserStopRequested() || WatchdogManager.isStarterActive) return

        if (ShizukuSettings.getLastLaunchMode() != ShizukuSettings.LaunchMethod.ADB) return

        if (!ShizukuSettings.getStartOnBoot(app) && !WatchdogManager.isEnabled()) return

        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (now - lastTriggerMs < DEBOUNCE_MS) return
            lastTriggerMs = now
        }

        scope.launch {
            try {
                val isRunning = withTimeoutOrNull(5_000L) {
                    val info = WorkManager.getInstance(app.applicationContext)
                        .getWorkInfosForUniqueWork(AdbStartWorker.UNIQUE_WORK_NAME)
                        .get()
                        .firstOrNull()
                    info?.state == WorkInfo.State.RUNNING
                } ?: false

                if (!isRunning) {
                    AdbStartWorker.enqueue(app.applicationContext)
                    ShizukuReceiverStarter.updateNotification(
                        app.applicationContext,
                        ShizukuReceiverStarter.WorkerState.AWAITING_WIFI
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "onUnmeteredAvailable enqueue failed", e)
            }
        }
    }

    private fun reArmWifiAfterSettle(app: Application) {
        scope.launch {
            delay(3_000)
            WifiDebugReassert.reassert(app.applicationContext)
        }
    }
}
