package moe.shizuku.manager.worker

import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.EOFException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbArm
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.AdbStarter
import moe.shizuku.manager.receiver.ShizukuReceiverStarter
import moe.shizuku.manager.receiver.ShizukuReceiverStarter.WorkerState
import moe.shizuku.manager.receiver.ShizukuReceiverStarter.updateNotification
import moe.shizuku.manager.settings.BugReportDialogActivity
import moe.shizuku.manager.service.WatchdogManager
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.ShizukuStateMachine
import rikka.shizuku.Shizuku

class AdbStartWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val UNIQUE_WORK_NAME = "adb_start_worker"
        const val CHANNEL_ID = "AdbStartWorker"
        const val NOTIFICATION_ID = 1448

        private const val DISCOVERY_TIMEOUT_MS = 15_000L
        private const val UNLOCK_WAIT_TIMEOUT_MS = 30_000L
        private const val BACKOFF_DELAY_MS = 30_000L

        /**
         * Enqueues the start worker.
         *
         * @param policy KEEP by default so a running worker is never cancelled
         *   (cancelling it would abort an mDNS discovery or an unlock wait);
         *   REPLACE is used when the user explicitly asks for another attempt.
         */
        fun enqueue(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
            val cb = Constraints.Builder()
            // Only constrain on an unmetered network when wireless discovery
            // actually needs Wi-Fi. In TCP mode the worker runs immediately.
            if (EnvironmentUtils.isWifiRequired()) {
                cb.setRequiredNetworkType(NetworkType.UNMETERED)
            }
            val constraints = cb.build()

            val request = OneTimeWorkRequestBuilder<AdbStartWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_MS, TimeUnit.MILLISECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                policy,
                request
            )
        }

        /**
         * Enqueues only when no start work is pending or running.
         * Never blocks: no Future.get(), so it is safe to call from a receiver
         * or a network callback.
         */
        fun enqueueIfIdle(context: Context) {
            if (ShizukuStateMachine.isRunning()) return
            enqueue(context, ExistingWorkPolicy.KEEP)
        }

        /** True when an unmetered, internet-capable network is available. */
        fun isUnmeteredNetworkAvailable(context: Context): Boolean {
            return try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as? android.net.ConnectivityManager ?: return false
                val network = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(network) ?: return false
                caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            } catch (_: Exception) {
                false
            }
        }

        /**
         * The banner state that matches what will actually happen next: while
         * Wi-Fi is the blocker show AWAITING_WIFI, otherwise RUNNING.
         */
        fun bannerStateFor(context: Context): WorkerState =
            if (!isUnmeteredNetworkAvailable(context)) WorkerState.AWAITING_WIFI else WorkerState.RUNNING
    }

    /**
     * Expedited work below API 31 runs inside a foreground service and
     * WorkManager fetches this notification before doWork() runs. The default
     * implementation throws, which would crash every watchdog-triggered ADB
     * restart on Android 7-11.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        ShizukuReceiverStarter.ensureChannel(applicationContext)
        val notification = NotificationCompat.Builder(applicationContext, ShizukuReceiverStarter.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(applicationContext.getString(R.string.wadb_notification_title))
            .setOngoing(true)
            .setSilent(true)
            .build()
        @Suppress("DEPRECATION")
        return ForegroundInfo(ShizukuReceiverStarter.NOTIFICATION_ID, notification)
    }

    override suspend fun doWork(): Result {
        try {
            if (WatchdogManager.isUserStopRequested()) {
                updateNotification(applicationContext, WorkerState.STOPPED)
                return Result.success()
            }
            updateNotification(applicationContext, WorkerState.RUNNING)

            // Gate every start path behind the lock screen. Android tears down
            // plain-TCP adb while the keyguard is up, so starting behind the
            // lockscreen just loops. Wait for USER_PRESENT, but bound the wait
            // so a locked device falls through to a backoff retry instead of
            // wedging the worker.
            val keyguardManager =
                applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            if (keyguardManager?.isKeyguardLocked == true) {
                val unlocked = withTimeoutOrNull(UNLOCK_WAIT_TIMEOUT_MS) {
                    awaitUserPresent(keyguardManager)
                }
                if (unlocked != true) {
                    updateNotification(applicationContext, WorkerState.AWAITING_RETRY)
                    return Result.retry()
                }
            }

            val cr = applicationContext.contentResolver

            // Arm adbd: USB debugging, wireless debugging and a non-expiring
            // pairing key window. Centralised in AdbArm so the permission
            // check cannot be forgotten at a call site.
            AdbArm.arm(applicationContext)

            val tcpPort = EnvironmentUtils.getAdbTcpPort()
            if (tcpPort > 0 && !ShizukuSettings.getTcpMode()) {
                // The device is still bound to TCP but TCP mode is off: return
                // adbd to USB mode so wireless debugging can be used again.
                AdbStarter.stopTcp(applicationContext, tcpPort)
            }

            val liveTcpPort = EnvironmentUtils.getLiveAdbTcpPort()

            val port = if (EnvironmentUtils.isTelevision()) {
                // TV devices with a configured TCP port use TCP directly;
                // mDNS discovery is unreliable on LEANBACK.
                if (tcpPort > 0) tcpPort else throw SecurityException("TV device requires TCP ADB port to be configured")
            } else if (!EnvironmentUtils.isWifiRequired() && liveTcpPort > 0) {
                // A configured port that is actually live can be used directly.
                liveTcpPort
            } else {
                // The mDNS advert can go stale when Wi-Fi drops and reconnects
                // (the framework never re-publishes _adb-tls-connect_). A live
                // port is used directly, otherwise discovery runs; a wrong
                // service connect dies fast, so the fallback is safe.
                if (liveTcpPort > 0) liveTcpPort else discoverPortViaMdns(cr)
            }

            // A user may have stopped the server while discovery was in
            // progress; do not let this worker undo that explicit request.
            if (WatchdogManager.isUserStopRequested()) {
                updateNotification(applicationContext, WorkerState.STOPPED)
                return Result.success()
            }

            AdbStarter.startAdb(applicationContext, port)
            try {
                Starter.waitForBinder()
            } catch (e: TimeoutException) {
                // The binder can arrive right after the wait timed out, so
                // re-ping once before treating this as a failure.
                if (!Shizuku.pingBinder()) throw e
            }

            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(ShizukuReceiverStarter.NOTIFICATION_ID)

            return Result.success()
        } catch (e: CancellationException) {
            if (WatchdogManager.isUserStopRequested()) {
                updateNotification(applicationContext, WorkerState.STOPPED)
                throw e
            }
            val state = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                WorkerState.AWAITING_RETRY
            } else {
                when (stopReason) {
                    WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> WorkerState.AWAITING_WIFI
                    WorkInfo.STOP_REASON_CANCELLED_BY_APP -> WorkerState.STOPPED
                    else -> WorkerState.AWAITING_RETRY
                }
            }
            updateNotification(applicationContext, state)

            throw e
        } catch (e: Exception) {
            val ignored = listOf(
                EOFException::class,
                SecurityException::class,
                TimeoutException::class,
                javax.net.ssl.SSLException::class,
                java.net.UnknownHostException::class
            )
            if (ignored.none { it.isInstance(e) }) showErrorNotification(applicationContext, e)

            if (ShizukuStateMachine.update() == ShizukuStateMachine.State.RUNNING) {
                // The binder arrived while unwinding: succeed and clear the
                // stale progress notification.
                val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(ShizukuReceiverStarter.NOTIFICATION_ID)
                return Result.success()
            }

            // The auto-start worker never terminally fails. adbd restarts, TLS
            // or key flaps, missed mDNS records and Wi-Fi bring-up races all
            // heal with backoff; failing here would strand the device until
            // the next boot or a manual start.
            updateNotification(applicationContext, WorkerState.AWAITING_RETRY)
            return Result.retry()
        }
    }

    /**
     * Suspends until the user unlocks the device.
     * Re-checks the keyguard after registering the receiver, because the device
     * may have been unlocked in between and USER_PRESENT would be missed.
     */
    private suspend fun awaitUserPresent(keyguardManager: KeyguardManager): Boolean =
        suspendCancellableCoroutine<Boolean> { cont ->
            val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
            val unlockReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == Intent.ACTION_USER_PRESENT) {
                        runCatching { applicationContext.unregisterReceiver(this) }
                        if (cont.isActive) cont.resumeWith(kotlin.Result.success(true))
                    }
                }
            }
            val receiverFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.RECEIVER_EXPORTED
            } else {
                ContextCompat.RECEIVER_NOT_EXPORTED
            }
            ContextCompat.registerReceiver(applicationContext, unlockReceiver, filter, receiverFlags)

            if (!keyguardManager.isKeyguardLocked) {
                runCatching { applicationContext.unregisterReceiver(unlockReceiver) }
                if (cont.isActive) cont.resumeWith(kotlin.Result.success(true))
            }
            cont.invokeOnCancellation {
                runCatching { applicationContext.unregisterReceiver(unlockReceiver) }
            }
        }

    /**
     * Discovers the wireless debugging port through mDNS.
     *
     * The wireless debugging flag can be cleared by the system while the
     * device is locked, in which case the worker waits for the unlock and
     * re-arms the flag. All waits are bounded so a flapping flag cannot wedge
     * the worker.
     */
    private suspend fun discoverPortViaMdns(cr: android.content.ContentResolver): Int = callbackFlow {
        val adbMdns = AdbMdns(applicationContext, AdbMdns.TLS_CONNECT) { p ->
            if (p.second > 0) trySend(p.second)
        }

        var awaitingAuth = false
        var timeoutJob: Job? = null
        var authWaitJob: Job? = null
        var unlockReceiver: BroadcastReceiver? = null

        fun startDiscoveryWithTimeout() {
            adbMdns.start()
            authWaitJob?.cancel()
            authWaitJob = null
            timeoutJob?.cancel()
            timeoutJob = launch {
                delay(DISCOVERY_TIMEOUT_MS)
                close(TimeoutException("Timed out during mDNS port discovery"))
            }
        }

        fun handleAuth() {
            val km = applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            timeoutJob?.cancel()
            timeoutJob = null
            adbMdns.stop()
            authWaitJob?.cancel()
            authWaitJob = null
            if (km.isKeyguardLocked) {
                if (unlockReceiver == null) {
                    val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            if (intent.action == Intent.ACTION_USER_PRESENT) {
                                runCatching { applicationContext.unregisterReceiver(this) }
                                unlockReceiver = null
                                AdbArm.armWifiEnabled(context)
                            }
                        }
                    }
                    val receiverFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        ContextCompat.RECEIVER_EXPORTED
                    } else {
                        ContextCompat.RECEIVER_NOT_EXPORTED
                    }
                    ContextCompat.registerReceiver(applicationContext, receiver, filter, receiverFlags)
                    unlockReceiver = receiver
                }
                // Bound the unlock wait: an uncapped wait wedges the worker when
                // the flag flaps during Wi-Fi bring-up.
                authWaitJob = launch {
                    delay(UNLOCK_WAIT_TIMEOUT_MS)
                    close(TimeoutException("Timed out waiting for unlock to authorize wireless debugging"))
                }
            } else {
                // The system cleared adb_wifi_enabled mid-run (typical during
                // Wi-Fi bring-up). Wait for it to be restored instead of
                // re-asserting it now and racing the cleanup.
                awaitingAuth = true
            }
        }

        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                when (Settings.Global.getInt(cr, AdbArm.ADB_WIFI_ENABLED, 0)) {
                    0 -> if (awaitingAuth) {
                        close(TimeoutException("Wireless debugging was disabled again mid-run"))
                    } else {
                        handleAuth()
                    }
                    1 -> {
                        awaitingAuth = false
                        startDiscoveryWithTimeout()
                    }
                }
            }
        }

        AdbArm.armWifiEnabled(applicationContext)
        val uri = Settings.Global.getUriFor(AdbArm.ADB_WIFI_ENABLED)
        if (uri != null) {
            cr.registerContentObserver(uri, false, observer)
        }
        startDiscoveryWithTimeout()

        awaitClose {
            adbMdns.stop()
            authWaitJob?.cancel()
            timeoutJob?.cancel()
            runCatching { cr.unregisterContentObserver(observer) }
            unlockReceiver?.let {
                runCatching { applicationContext.unregisterReceiver(it) }
                unlockReceiver = null
            }
        }
    }.first()

    private fun showErrorNotification(context: Context, e: Exception) {
        ShizukuReceiverStarter.ensureChannel(context)

        val intent = Intent(context, BugReportDialogActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val msgNotif = "$e. ${context.getString(R.string.wadb_error_notify_dev)}"

        val notification = NotificationCompat.Builder(context, ShizukuReceiverStarter.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(context.getString(R.string.wadb_error_title))
            .setContentText(msgNotif)
            .setContentIntent(pendingIntent)
            .setSilent(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msgNotif))
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }
}
