package moe.shizuku.manager.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.topjohnwu.superuser.Shell
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ktx.logd
import moe.shizuku.manager.ktx.logi
import moe.shizuku.manager.ktx.logw
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.utils.SettingsPage
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.worker.AdbStartWorker
import rikka.shizuku.Shizuku

object WatchdogManager {

    const val CRASH_CHANNEL_ID = "crash_reports"
    private const val NOTIFICATION_ID_CRASH = 1002

    private const val EXPECTED_DEATH_WINDOW_MS = 30_000L
    private const val MIN_RESTART_INTERVAL_MS = 15_000L
    private const val MAX_RECOVERY_ATTEMPTS = 5
    private const val KEY_USER_STOP_REQUESTED = "watchdog_user_stop_requested"

    private val BACKOFF_DELAYS_MS = longArrayOf(15_000L, 30_000L, 60_000L, 120_000L)

    @Volatile
    var isStarterActive = false

    @Volatile
    var expectingDeath = false
        set(value) {
            field = value
            expectedDeathDeadlineMillis = if (value) {
                SystemClock.elapsedRealtime() + EXPECTED_DEATH_WINDOW_MS
            } else {
                0L
            }
        }

    @Volatile
    private var expectedDeathDeadlineMillis = 0L

    @Volatile
    private var initialized = false

    private val restartInProgress = AtomicBoolean(false)

    @Volatile
    private var lastRestartAttemptMs = 0L

    private val recoveryAttempts = AtomicInteger(0)

    @Volatile
    private var userStopRequested = false

    data class HealthResult(
        val healthy: Boolean,
        val reason: String,
        val binderAlive: Boolean
    )

    fun init(context: Context) {
        val appContext = context.applicationContext
        if (initialized) return
        initialized = true

        userStopRequested = ShizukuSettings.getPreferences()?.getBoolean(KEY_USER_STOP_REQUESTED, false) ?: false

        logi("Watchdog: initialized")

        Shizuku.addBinderReceivedListenerSticky {
            logi("Binder received")
            expectingDeath = false
            recoveryAttempts.set(0)
            clearUserStopRequest(appContext)
        }

        Shizuku.addBinderDeadListener {
            logw("Binder died")
            onServiceDied(appContext)
        }
    }

    fun isEnabled(): Boolean {
        return ShizukuSettings.getWatchdog()
    }

    fun isExpectingDeathActive(): Boolean {
        if (isStarterActive) return true
        if (!expectingDeath) return false
        val deadline = expectedDeathDeadlineMillis
        if (deadline == 0L) return true
        return SystemClock.elapsedRealtime() <= deadline
    }

    fun shouldRunService(): Boolean {
        return isEnabled() && !isUserStopRequested()
    }

    fun reconcileService(context: Context) {
        val appContext = context.applicationContext
        if (shouldRunService()) {
            WatchdogService.start(appContext)
        } else {
            WatchdogService.stop(appContext)
        }
    }

    fun onServiceDied(context: Context) {
        if (isStarterActive) {
            logi("Starter active, suppressing watchdog restart")
            return
        }

        if (consumeExpectedDeath()) {
            logi("Watchdog: death was expected")
            return
        }

        if (isUserStopRequested()) {
            logi("Watchdog: user stop requested")
            return
        }

        showCrashNotification(context)

        if (isEnabled()) {
            attemptRestart(context)
        }
    }

    private fun consumeExpectedDeath(): Boolean {
        if (!expectingDeath) return false
        val now = SystemClock.elapsedRealtime()
        val deadline = expectedDeathDeadlineMillis
        expectingDeath = false
        if (deadline == 0L || now <= deadline) {
            return true
        }
        logd("Ignoring stale expected-death flag")
        return false
    }

    fun clearUserStopRequest(context: Context? = null) {
        setUserStopRequested(false)
        expectingDeath = false
        context?.let { reconcileService(it) }
    }

    fun setUserStopRequested(value: Boolean) {
        userStopRequested = value
        ShizukuSettings.getPreferences()?.edit()
            ?.putBoolean(KEY_USER_STOP_REQUESTED, value)
            ?.apply()
    }

    fun isUserStopRequested(): Boolean {
        return userStopRequested || (ShizukuSettings.getPreferences()?.getBoolean(KEY_USER_STOP_REQUESTED, false) ?: false)
    }

    fun checkHealth(): HealthResult {
        try {
            val binder = Shizuku.getBinder()
                ?: return HealthResult(false, "binder is null", false)
            val ping = try {
                Shizuku.pingBinder() && binder.pingBinder()
            } catch (e: Throwable) {
                false
            }
            if (!ping) {
                return HealthResult(false, "pingBinder() failed", false)
            }
            val version = try {
                Shizuku.getVersion()
            } catch (e: Throwable) {
                return HealthResult(false, "binder transaction failed: ${e.javaClass.simpleName}", true)
            }
            if (version <= 0) {
                return HealthResult(false, "bad remote version=$version", true)
            }
            val uid = try {
                Shizuku.getUid()
            } catch (e: Throwable) {
                return HealthResult(false, "getUid transaction failed: ${e.javaClass.simpleName}", true)
            }
            if (uid < 0) {
                return HealthResult(false, "bad remote uid=$uid", true)
            }
            return HealthResult(true, "ok version=$version uid=$uid", true)
        } catch (e: Throwable) {
            return HealthResult(false, "check threw ${e.javaClass.simpleName}: ${e.message}", Shizuku.pingBinder())
        }
    }

    fun attemptRestart(context: Context) {
        val appContext = context.applicationContext

        if (isStarterActive) {
            logi("Recovery skipped (starter active)")
            return
        }

        if (isUserStopRequested()) {
            logi("Recovery skipped (user stop requested)")
            return
        }

        if (!isEnabled()) {
            logi("Recovery skipped (watchdog disabled)")
            return
        }

        val lastMode = ShizukuSettings.getLastLaunchMode()
        if (lastMode == ShizukuSettings.LaunchMethod.UNKNOWN) {
            logd("Recovery skipped: UNKNOWN launch mode")
            return
        }

        val attempts = recoveryAttempts.get()
        if (attempts >= MAX_RECOVERY_ATTEMPTS) {
            logw("Watchdog: maximum recovery attempts reached")
            return
        }

        val now = SystemClock.elapsedRealtime()
        val backoffDelayIndex = (attempts - 1).coerceIn(0, BACKOFF_DELAYS_MS.lastIndex)
        val requiredCooldown = if (attempts > 0) BACKOFF_DELAYS_MS[backoffDelayIndex] else MIN_RESTART_INTERVAL_MS

        if (now - lastRestartAttemptMs < requiredCooldown) {
            logd("Watchdog: cooldown active")
            return
        }

        if (!restartInProgress.compareAndSet(false, true)) {
            logd("Recovery skipped: already in progress")
            return
        }
        lastRestartAttemptMs = now

        CoroutineScope(Dispatchers.IO).launch {
            try {
                logi("Watchdog: recovery started (mode=$lastMode, attempt=${attempts + 1})")

                when (lastMode) {
                    ShizukuSettings.LaunchMethod.ROOT -> restartRoot()
                    ShizukuSettings.LaunchMethod.ADB -> restartAdb(appContext)
                    else -> logd("Unknown launch mode: $lastMode")
                }

                val binderReceived = ShizukuStateMachine.awaitRunning(15_000L)
                if (binderReceived) {
                    val health = checkHealth()
                    if (health.healthy) {
                        logi("Watchdog: recovery succeeded")
                        recoveryAttempts.set(0)
                        return@launch
                    } else {
                        logw("Watchdog: zombie binder detected (${health.reason})")
                    }
                } else {
                    logw("Watchdog: recovery failed (timeout waiting for binder)")
                }

                val newAttempts = recoveryAttempts.incrementAndGet()
                logw("Watchdog: recovery failed (attempt $newAttempts/$MAX_RECOVERY_ATTEMPTS)")
                if (newAttempts >= MAX_RECOVERY_ATTEMPTS) {
                    logw("Watchdog: maximum recovery attempts reached")
                }
            } catch (t: Throwable) {
                logw("Watchdog: recovery failed with exception: ${t.message}")
            } finally {
                restartInProgress.set(false)
            }
        }
    }

    private fun restartRoot() {
        try {
            if (!Shell.getShell().isRoot) {
                Shell.getCachedShell()?.close()
            }
            if (Shell.getShell().isRoot) {
                ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)
                Shell.cmd(Starter.internalCommand).exec()
            } else {
                logw("Cannot restart via root: device is not rooted")
            }
        } catch (e: Exception) {
            logw("Root restart failed: ${e.message}")
        }
    }

    private fun restartAdb(context: Context) {
        AdbStartWorker.enqueueIfIdle(context.applicationContext)
    }

    fun requestStopServer(context: Context? = null, userInitiated: Boolean = true): Throwable? {
        if (userInitiated) {
            setUserStopRequested(true)
            context?.let { reconcileService(it) }
        }
        expectingDeath = true
        ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPING)
        return try {
            Shizuku.exit()
            null
        } catch (e: Throwable) {
            logd("Failed to stop server via Shizuku.exit: ${e.message}")
            expectingDeath = false
            e
        }
    }

    suspend fun stopServerAndWait(
        context: Context? = null,
        userInitiated: Boolean = true,
        timeoutMs: Long = 3_000L
    ): Boolean {
        requestStopServer(context, userInitiated)
        return ShizukuStateMachine.awaitStopped(timeoutMs)
    }

    fun showCrashNotification(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CRASH_CHANNEL_ID,
            "Crash Reports",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        nm.createNotificationChannel(channel)

        val learnMoreIntent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://github.com/thedjchi/Shizuku/wiki#shizuku-keeps-stopping-randomly")
        }
        val learnMorePendingIntent = PendingIntent.getActivity(
            context, 0, learnMoreIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val disableIntent = SettingsPage.Notifications.NotificationChannel.buildIntent(context)
        val disablePendingIntent = PendingIntent.getActivity(
            context, 0, disableIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CRASH_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.watchdog_shizuku_crashed_title))
            .setContentText(context.getString(R.string.watchdog_shizuku_crashed_text))
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentIntent(learnMorePendingIntent)
            .setAutoCancel(true)
            .addAction(0, context.getString(R.string.watchdog_shizuku_crashed_action_turn_off_alerts), disablePendingIntent)
            .build()

        nm.notify(NOTIFICATION_ID_CRASH, notification)
    }
}
