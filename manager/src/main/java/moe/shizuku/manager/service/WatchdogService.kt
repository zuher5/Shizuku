package moe.shizuku.manager.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ktx.logd
import moe.shizuku.manager.ktx.logi
import moe.shizuku.manager.ktx.logw
import moe.shizuku.manager.utils.ShizukuStateMachine

class WatchdogService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private var healthCheckJob: Job? = null

    private val healthCheckIntervalMs = 30_000L

    private val stateListener: (ShizukuStateMachine.State) -> Unit = { state ->
        if (state == ShizukuStateMachine.State.CRASHED) {
            logw("WatchdogService: observed CRASHED state")
            serviceScope.launch {
                handleCrashState()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning.set(true)
        WatchdogManager.init(applicationContext)
        ShizukuStateMachine.addListener(stateListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE || intent?.action == "ACTION_STOP_SERVICE") {
            logi("WatchdogService: stop requested by user action")
            ShizukuSettings.setWatchdog(applicationContext, false)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!WatchdogManager.shouldRunService()) {
            stopSelf()
            return START_NOT_STICKY
        }

        isRunning.set(true)
        startAsForeground()
        startHealthCheckLoop()

        return START_STICKY
    }

    override fun onDestroy() {
        ShizukuStateMachine.removeListener(stateListener)
        stopHealthCheckLoop()
        serviceJob.cancel()
        isRunning.set(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun handleCrashState() {
        if (!WatchdogManager.shouldRunService()) {
            logd("WatchdogService: watchdog disabled, skipping crash restart")
            return
        }
        if (WatchdogManager.isExpectingDeathActive()) {
            logd("WatchdogService: death expected or starter active, skipping crash restart")
            return
        }
        if (WatchdogManager.isUserStopRequested()) {
            logi("WatchdogService: stop was user-initiated, skipping crash restart")
            return
        }
        if (ShizukuSettings.getLastLaunchMode() == ShizukuSettings.LaunchMethod.UNKNOWN) {
            logd("WatchdogService: server never started (UNKNOWN mode), skipping crash restart")
            return
        }

        withContext(Dispatchers.IO) {
            WatchdogManager.attemptRestart(applicationContext)
        }
    }

    private fun startHealthCheckLoop() {
        healthCheckJob?.cancel()
        if (!WatchdogManager.isEnabled()) return

        healthCheckJob = serviceScope.launch {
            var consecutiveFailures = 0
            logi("Watchdog: health check loop started")
            while (isActive) {
                delay(healthCheckIntervalMs)
                if (!WatchdogManager.isEnabled()) break

                if (WatchdogManager.isRestartInProgress()) {
                    logd("Watchdog: restart in progress, skipping periodic health check")
                    continue
                }

                if (WatchdogManager.isExpectingDeathActive() || WatchdogManager.isStarterActive) {
                    logd("Watchdog: death expected or starter active, skipping periodic health check")
                    consecutiveFailures = 0
                    continue
                }

                val result = WatchdogManager.checkHealth()
                if (result.healthy) {
                    if (consecutiveFailures > 0) {
                        logi("Watchdog: health check passed (${result.reason})")
                    }
                    consecutiveFailures = 0
                } else {
                    consecutiveFailures++
                    logw("Watchdog: health check failed [$consecutiveFailures/$FAILURES_TO_RESTART]: ${result.reason}")
                    if (consecutiveFailures >= FAILURES_TO_RESTART) {
                        consecutiveFailures = 0
                        handleUnhealthy(result)
                        if (!isActive) break
                    }
                }
            }
            logi("Watchdog: health check loop stopped")
        }
    }

    private suspend fun handleUnhealthy(result: WatchdogManager.HealthResult) {
        if (!WatchdogManager.shouldRunService()) return
        if (WatchdogManager.isExpectingDeathActive()) return
        if (WatchdogManager.isUserStopRequested()) return
        if (ShizukuSettings.getLastLaunchMode() == ShizukuSettings.LaunchMethod.UNKNOWN) return

        withContext(Dispatchers.IO) {
            if (result.status == WatchdogManager.HealthStatus.ZOMBIE || result.binderAlive) {
                logw("Watchdog: zombie binder detected (${result.reason}). Stopping server before restart...")
                WatchdogManager.requestStopServer(applicationContext, userInitiated = false)
                ShizukuStateMachine.awaitStopped(3_000L)
            } else {
                logw("Watchdog: binder dead (${result.reason}). Attempting restart...")
            }
            WatchdogManager.attemptRestart(applicationContext)
        }
    }

    private fun stopHealthCheckLoop() {
        healthCheckJob?.cancel()
        healthCheckJob = null
    }

    private fun startAsForeground() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID_WATCHDOG,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(
                    NOTIFICATION_ID_WATCHDOG,
                    buildNotification()
                )
            }
        } catch (e: Throwable) {
            logw("WatchdogService: startForeground failed: ${e.message}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is android.app.ForegroundServiceStartNotAllowedException
            ) {
                stopSelf()
            }
        }
    }

    private fun buildNotification(): Notification {
        val channelId = CHANNEL_ID
        val channelName = CHANNEL_NAME

        val channel = NotificationChannel(
            channelId,
            channelName,
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        val launchPendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, WatchdogService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.watchdog_running))
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentIntent(launchPendingIntent)
            .addAction(
                R.drawable.ic_close_24,
                getString(R.string.watchdog_turn_off),
                stopPendingIntent
            )
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val ACTION_STOP_SERVICE = "moe.shizuku.manager.action.STOP_WATCHDOG_SERVICE"
        private const val CHANNEL_ID = "shizuku_watchdog"
        private const val CHANNEL_NAME = "Watchdog"
        private const val NOTIFICATION_ID_WATCHDOG = 1001
        private const val FAILURES_TO_RESTART = 2

        const val CRASH_CHANNEL_ID = WatchdogManager.CRASH_CHANNEL_ID

        private val isRunning = AtomicBoolean(false)

        @JvmStatic
        fun start(context: Context) {
            try {
                val intent = Intent(context, WatchdogService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                logw("Failed to start WatchdogService: ${e.message}")
            }
        }

        @JvmStatic
        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, WatchdogService::class.java))
            } catch (e: Exception) {
                logw("Failed to stop WatchdogService: ${e.message}")
            }
        }

        @JvmStatic
        fun reconcile(context: Context) {
            WatchdogManager.reconcileService(context)
        }

        @JvmStatic
        fun isRunning(): Boolean = isRunning.get()
    }
}
