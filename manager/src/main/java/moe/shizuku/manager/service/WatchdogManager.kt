package moe.shizuku.manager.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.DeadObjectException
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.topjohnwu.superuser.Shell
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    private const val HEALTH_CHECK_TIMEOUT_MS = 3_000L

    private val BACKOFF_DELAYS_MS = longArrayOf(15_000L, 30_000L, 60_000L, 120_000L)

    @Volatile
    var isStarterActive = false

    private val expectedDeathDeadlineMillis = AtomicLong(0L)

    var expectingDeath: Boolean
        get() = isExpectingDeathActive()
        set(value) {
            if (value) {
                expectedDeathDeadlineMillis.set(SystemClock.elapsedRealtime() + EXPECTED_DEATH_WINDOW_MS)
            } else {
                expectedDeathDeadlineMillis.set(0L)
            }
        }

    private val initialized = AtomicBoolean(false)
    private val restartInProgress = AtomicBoolean(false)

    @Volatile
    private var lastRestartAttemptMs = 0L

    private val recoveryAttempts = AtomicInteger(0)
    private val userStopRequested = AtomicBoolean(false)

    @Volatile
    private var appContext: Context? = null

    private val admissionLock = Any()
    private val watchdogJob = SupervisorJob()
    private val watchdogScope = CoroutineScope(watchdogJob + Dispatchers.IO)

    private val healthCheckInProgress = AtomicBoolean(false)
    private val healthCheckExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "watchdog-health-check").apply { isDaemon = true }
    }

    enum class HealthStatus {
        HEALTHY,
        ZOMBIE,
        DEAD
    }

    data class HealthResult(
        val status: HealthStatus,
        val reason: String,
        val healthy: Boolean = status == HealthStatus.HEALTHY,
        val binderAlive: Boolean = status != HealthStatus.DEAD
    ) {
        constructor(healthy: Boolean, reason: String, binderAlive: Boolean) : this(
            status = if (healthy) HealthStatus.HEALTHY else if (binderAlive) HealthStatus.ZOMBIE else HealthStatus.DEAD,
            reason = reason,
            healthy = healthy,
            binderAlive = binderAlive
        )
    }

    sealed class AdmissionResult {
        data class Admitted(val mode: Int, val attempts: Int, val delayMs: Long) : AdmissionResult()
        data class Cooldown(val remainingMs: Long) : AdmissionResult()
        data class Skipped(val reason: String) : AdmissionResult()
        object Exhausted : AdmissionResult()
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        logi("Binder received")
        expectedDeathDeadlineMillis.set(0L)
        recoveryAttempts.set(0)
        val context = appContext
        if (context != null) {
            clearUserStopRequest(context)
        } else {
            clearUserStopRequest()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        logw("Binder died")
        val context = appContext
        if (context != null) {
            onServiceDied(context)
        }
    }

    fun init(context: Context) {
        val app = context.applicationContext
        synchronized(admissionLock) {
            if (this.appContext == null) {
                this.appContext = app
            }
        }
        if (!initialized.compareAndSet(false, true)) return

        userStopRequested.set(
            ShizukuSettings.getPreferences()?.getBoolean(KEY_USER_STOP_REQUESTED, false) ?: false
        )

        logi("Watchdog: initialized")

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
    }

    fun isEnabled(): Boolean {
        return ShizukuSettings.getWatchdog()
    }

    fun isExpectingDeathActive(): Boolean {
        if (isStarterActive) return true
        val deadline = expectedDeathDeadlineMillis.get()
        if (deadline == 0L) return false
        val now = SystemClock.elapsedRealtime()
        if (now <= deadline) return true
        expectedDeathDeadlineMillis.compareAndSet(deadline, 0L)
        return false
    }

    fun shouldRunService(): Boolean {
        return isEnabled() && !isUserStopRequested()
    }

    fun reconcileService(context: Context) {
        val app = context.applicationContext
        synchronized(admissionLock) {
            if (this.appContext == null) {
                this.appContext = app
            }
        }
        if (shouldRunService()) {
            WatchdogService.start(app)
        } else {
            WatchdogService.stop(app)
        }
    }

    fun onServiceDied(context: Context) {
        val app = context.applicationContext
        synchronized(admissionLock) {
            if (this.appContext == null) {
                this.appContext = app
            }
        }

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

        showCrashNotification(app)

        if (isEnabled()) {
            attemptRestart(app)
        }
    }

    private fun consumeExpectedDeath(): Boolean {
        val deadline = expectedDeathDeadlineMillis.getAndSet(0L)
        if (deadline == 0L) return false
        val now = SystemClock.elapsedRealtime()
        if (now <= deadline) {
            return true
        }
        logd("Ignoring stale expected-death flag")
        return false
    }

    fun clearUserStopRequest(context: Context? = null) {
        setUserStopRequested(false)
        expectedDeathDeadlineMillis.set(0L)
        recoveryAttempts.set(0)
        context?.let { reconcileService(it) }
    }

    fun setUserStopRequested(value: Boolean) {
        userStopRequested.set(value)
        ShizukuSettings.getPreferences()?.edit()
            ?.putBoolean(KEY_USER_STOP_REQUESTED, value)
            ?.apply()
    }

    fun isUserStopRequested(): Boolean {
        if (initialized.get()) {
            return userStopRequested.get()
        }
        return ShizukuSettings.getPreferences()?.getBoolean(KEY_USER_STOP_REQUESTED, false) ?: false
    }

    fun getRequiredCooldown(attempts: Int): Long {
        if (attempts <= 0) return MIN_RESTART_INTERVAL_MS
        val index = (attempts - 1).coerceIn(0, BACKOFF_DELAYS_MS.lastIndex)
        return BACKOFF_DELAYS_MS[index]
    }

    fun getRecoveryAttempts(): Int = recoveryAttempts.get()

    fun isRestartInProgress(): Boolean = restartInProgress.get()

    fun tryAdmitRestart(): AdmissionResult {
        synchronized(admissionLock) {
            if (!isEnabled()) {
                return AdmissionResult.Skipped("watchdog disabled")
            }
            if (isUserStopRequested()) {
                return AdmissionResult.Skipped("user stop requested")
            }
            if (isStarterActive) {
                return AdmissionResult.Skipped("starter active")
            }
            if (isExpectingDeathActive()) {
                return AdmissionResult.Skipped("death expected")
            }
            val lastMode = ShizukuSettings.getLastLaunchMode()
            if (lastMode == ShizukuSettings.LaunchMethod.UNKNOWN) {
                return AdmissionResult.Skipped("UNKNOWN launch mode")
            }
            val attempts = recoveryAttempts.get()
            if (attempts >= MAX_RECOVERY_ATTEMPTS) {
                return AdmissionResult.Exhausted
            }
            val now = SystemClock.elapsedRealtime()
            val requiredCooldown = getRequiredCooldown(attempts)
            val elapsed = now - lastRestartAttemptMs
            if (lastRestartAttemptMs > 0L && elapsed < requiredCooldown) {
                return AdmissionResult.Cooldown(requiredCooldown - elapsed)
            }
            if (!restartInProgress.compareAndSet(false, true)) {
                return AdmissionResult.Skipped("already in progress")
            }
            lastRestartAttemptMs = now
            return AdmissionResult.Admitted(lastMode, attempts, requiredCooldown)
        }
    }

    fun checkHealth(): HealthResult {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            logw("Watchdog: checkHealth() called on main thread!")
        }

        if (!healthCheckInProgress.compareAndSet(false, true)) {
            return HealthResult(
                HealthStatus.ZOMBIE,
                "health check already in progress or previously hung in transaction"
            )
        }

        val future = healthCheckExecutor.submit(Callable {
            try {
                performDirectHealthCheck()
            } finally {
                healthCheckInProgress.set(false)
            }
        })

        return try {
            future.get(HEALTH_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            HealthResult(
                HealthStatus.ZOMBIE,
                "binder transaction timed out after ${HEALTH_CHECK_TIMEOUT_MS}ms"
            )
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            HealthResult(
                HealthStatus.DEAD,
                "binder check failed: ${cause.javaClass.simpleName} - ${cause.message}"
            )
        } catch (e: InterruptedException) {
            HealthResult(
                HealthStatus.DEAD,
                "binder check interrupted"
            )
        }
    }

    private fun performDirectHealthCheck(): HealthResult {
        return try {
            val binder = Shizuku.getBinder()
                ?: return HealthResult(HealthStatus.DEAD, "binder is null")

            if (!binder.isBinderAlive) {
                return HealthResult(HealthStatus.DEAD, "binder is not alive")
            }

            val ping = try {
                Shizuku.pingBinder() && binder.pingBinder()
            } catch (e: Throwable) {
                false
            }
            if (!ping) {
                return HealthResult(HealthStatus.DEAD, "pingBinder() failed")
            }

            val version = try {
                Shizuku.getVersion()
            } catch (e: Throwable) {
                val isDead = e is DeadObjectException || !binder.isBinderAlive
                val status = if (isDead) HealthStatus.DEAD else HealthStatus.ZOMBIE
                return HealthResult(status, "getVersion failed: ${e.javaClass.simpleName} - ${e.message}")
            }
            if (version <= 0) {
                return HealthResult(HealthStatus.ZOMBIE, "bad remote version=$version")
            }

            val uid = try {
                Shizuku.getUid()
            } catch (e: Throwable) {
                val isDead = e is DeadObjectException || !binder.isBinderAlive
                val status = if (isDead) HealthStatus.DEAD else HealthStatus.ZOMBIE
                return HealthResult(status, "getUid failed: ${e.javaClass.simpleName} - ${e.message}")
            }
            if (uid < 0) {
                return HealthResult(HealthStatus.ZOMBIE, "bad remote uid=$uid")
            }

            HealthResult(HealthStatus.HEALTHY, "ok version=$version uid=$uid")
        } catch (e: Throwable) {
            val alive = try { Shizuku.pingBinder() } catch (t: Throwable) { false }
            val status = if (alive) HealthStatus.ZOMBIE else HealthStatus.DEAD
            HealthResult(status, "check threw ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun attemptRestart(context: Context) {
        val app = context.applicationContext
        synchronized(admissionLock) {
            if (this.appContext == null) {
                this.appContext = app
            }
        }

        val admission = tryAdmitRestart()
        when (admission) {
            is AdmissionResult.Skipped -> {
                logd("Recovery skipped (${admission.reason})")
                return
            }
            is AdmissionResult.Exhausted -> {
                logw("Watchdog: maximum recovery attempts reached")
                return
            }
            is AdmissionResult.Cooldown -> {
                logd("Watchdog: cooldown active (${admission.remainingMs}ms remaining)")
                return
            }
            is AdmissionResult.Admitted -> {
                // Proceed
            }
        }

        val admitted = admission as AdmissionResult.Admitted
        val attemptNum = admitted.attempts + 1

        watchdogScope.launch {
            try {
                logi("Watchdog: recovery started (mode=${admitted.mode}, attempt=$attemptNum/$MAX_RECOVERY_ATTEMPTS, delay=${admitted.delayMs}ms)")

                when (admitted.mode) {
                    ShizukuSettings.LaunchMethod.ROOT -> restartRoot()
                    ShizukuSettings.LaunchMethod.ADB -> restartAdb(app)
                    else -> logd("Unknown launch mode: ${admitted.mode}")
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
                        requestStopServer(app, userInitiated = false)
                        ShizukuStateMachine.awaitStopped(3_000L)
                    }
                } else {
                    logw("Watchdog: recovery failed (timeout waiting for binder)")
                }

                val newAttempts = recoveryAttempts.incrementAndGet()
                logw("Watchdog: recovery failed (attempt $newAttempts/$MAX_RECOVERY_ATTEMPTS)")
                if (newAttempts >= MAX_RECOVERY_ATTEMPTS) {
                    logw("Watchdog: maximum recovery attempts reached")
                }
            } catch (e: CancellationException) {
                logd("Watchdog: recovery cancelled")
                throw e
            } catch (t: Throwable) {
                val newAttempts = recoveryAttempts.incrementAndGet()
                logw("Watchdog: recovery failed with exception (attempt $newAttempts/$MAX_RECOVERY_ATTEMPTS): ${t.message}")
                if (newAttempts >= MAX_RECOVERY_ATTEMPTS) {
                    logw("Watchdog: maximum recovery attempts reached")
                }
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
