package moe.shizuku.manager.worker

import android.app.Application
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
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.receiver.ShizukuReceiverStarter
import rikka.shizuku.Shizuku

/**
 * Safety net for the "booted on cellular, never restarted on Wi-Fi" case.
 *
 * The boot worker carries an UNMETERED constraint, so WorkManager resumes it
 * when Wi-Fi returns - but only while the work is still pending. Work that
 * already terminally failed (older app versions, user-cancelled retries)
 * stays dead. This observer re-enqueues an idle start worker whenever an
 * unmetered network becomes available, so returning to Wi-Fi restarts Shizuku
 * even in those stranded states.
 */
object AdbNetworkObserver {

    private const val TAG = "AdbNetworkObserver"

    @Volatile
    private var registered = false

    /** Suppresses back-to-back onAvailable flaps racing the constraint unblock. */
    @Volatile
    private var lastTriggerMs = 0L
    private const val DEBOUNCE_MS = 5_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Strong reference: ConnectivityManager does not retain the callback, so an
     * anonymous instance would be garbage collected and silently kill the
     * observer.
     */
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Never unregistered: process-scoped singleton, registered once from
     * Application.onCreate. Process death cleans it up and the registered flag
     * guards double observation on re-entry.
     */
    fun register(app: Application) {
        // Wireless debugging needs API 30+; on older devices there is nothing
        // to observe.
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
                // Observing is best-effort; the constrained worker covers the
                // normal path on its own.
                Log.w(TAG, "registerNetworkCallback failed", e)
            }
        }
    }

    private fun onUnmeteredAvailable(app: Application) {
        if (Shizuku.pingBinder()) {
            // The "awaiting Wi-Fi" banner is definitively stale, so clear it.
            // This runs before the debounce so a no-op binder-up flap does not
            // burn the window a real unstarted case would have to wait out.
            ShizukuReceiverStarter.updateNotification(
                app.applicationContext,
                ShizukuReceiverStarter.WorkerState.STOPPED
            )
            return
        }

        if (!ShizukuSettings.getStartOnBoot(app.applicationContext)) return
        if (ShizukuSettings.getLastLaunchMode() != ShizukuSettings.LaunchMethod.ADB) return

        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (now - lastTriggerMs < DEBOUNCE_MS) return
            lastTriggerMs = now
        }

        scope.launch {
            try {
                // Unmetered Wi-Fi is back. Actively RUNNING work is left alone:
                // REPLACE would cancel a live discovery mid-flight.
                val isRunning = isWorkerRunning(app)
                if (!isRunning) {
                    AdbStartWorker.enqueue(app.applicationContext)
                }
                val state = if (isRunning) {
                    ShizukuReceiverStarter.WorkerState.RUNNING
                } else {
                    AdbStartWorker.bannerStateFor(app.applicationContext)
                }
                ShizukuReceiverStarter.updateNotification(app.applicationContext, state)
            } catch (e: Exception) {
                Log.w(TAG, "onUnmeteredAvailable enqueue failed", e)
            }
        }
    }

    /**
     * Reads the worker state without blocking the caller: WorkManager's
     * ListenableFuture is awaited on a background dispatcher with a bounded
     * timeout, so a slow database never stalls the network callback.
     */
    private suspend fun isWorkerRunning(app: Application): Boolean = try {
        val future = WorkManager.getInstance(app.applicationContext)
            .getWorkInfosForUniqueWork(AdbStartWorker.UNIQUE_WORK_NAME)
        future.get(5, java.util.concurrent.TimeUnit.SECONDS)
            .any { it.state == WorkInfo.State.RUNNING }
    } catch (e: Exception) {
        Log.w(TAG, "failed to read worker state", e)
        false
    }
}
