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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
    private const val MAX_RECOVERY_ATTEMPTS = 5
    private const val KEY_USER_STOP_REQUESTED = "watchdog_user_stop_requested"
    private const val HEALTH_CHECK_TIMEOUT_MS = 3_000L
    private const val HEALTH_CHECK_STALE_TIMEOUT_MS = 6_000L

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

    @Volatile
    private var scheduledRetryJob: Job? = null

    private val healthCheckInProgress = AtomicBoolean(false)
    private val healthCheckStartTimeMs = AtomicLong(0L)
    private val healthCheckGeneration = AtomicLong(0L)
    private val healthCheckExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "watchdog-health-check").apply { isDaemon = true }
    }

    @Volatile
    internal var healthProbe: (() -> HealthResult)? = null

    enum class HealthStatus {
        HEALTHY,
        ZOMBIE,
        DEAD
    }

    data class HealthResult(
        val status: HealthStatus,
        val reason: String,
        val healthy: Boolean = status == HealthStatus.HEALTHY,
        val binderAlive: Boolean = false
    )

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
        cancelScheduledRetry()
        resetHealthCheckState()
        val context = appContext
        if (context != null) {
            clearUserStopRequest(context)
        } else {
            clearUserStopRequest()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        logw("Binder died")
        resetHealthCheckState()
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
        cancelScheduledRetry()
        resetHealthCheckState()
        setUserStopRequested(false)
        expectedDeathDeadlineMillis.set(0L)
        recoveryAttempts.set(0)
        context?.let { reconcileService(it) }
    }

    fun setUserStopRequested(value: Boolean) {
        userStopRequested.set(value)
        if (value) {
            cancelScheduledRetry()
        }
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

    fun isRestartInProgress(): Boolean = restartInProgress.get()

    fun resetHealthCheckState() {
        healthCheckGeneration.incrementAndGet()
        healthCheckStartTimeMs.set(0L)
        healthCheckInProgress.set(false)
    }

    private fun scheduleRetry(context: Context, attempts: Int) {
        synchronized(admissionLock) {
            scheduledRetryJob?.cancel()
            if (!shouldRunService() || isUserStopRequested() || isExpectingDeathActive()) {
                return
            }
            val delayMs = WatchdogDecisions.getRequiredCooldown(attempts)
            logi("Watchdog: scheduling next recovery attempt ($attempts/$MAX_RECOVERY_ATTEMPTS) in ${delayMs}ms")
            scheduledRetryJob = watchdogScope.launch {
                delay(delayMs)
                if (!shouldRunService() || isUserStopRequested() || isExpectingDeathActive()) {
                    logd("Watchdog: scheduled retry discarded due to state change")
                    return@launch
                }
                attemptRestart(context)
            }
        }
    }

    fun cancelScheduledRetry() {
        synchronized(admissionLock) {
            scheduledRetryJob?.cancel()
            scheduledRetryJob = null
        }
    }

    fun tryAdmitRestart(): AdmissionResult {
        synchronized(admissionLock) {
            val result = WatchdogDecisions.evaluateAdmission(
                isEnabled = isEnabled(),
                isUserStopRequested = isUserStopRequested(),
                isStarterActive = isStarterActive,
                isExpectingDeathActive = isExpectingDeathActive(),
                launchMode = ShizukuSettings.getLastLaunchMode(),
                attempts = recoveryAttempts.get(),
                now = SystemClock.elapsedRealtime(),
                lastRestartAttemptMs = lastRestartAttemptMs,
                isRestartInProgress = restartInProgress.get()
            )
            if (result is AdmissionResult.Admitted) {
                if (!restartInProgress.compareAndSet(false, true)) {
                    return AdmissionResult.Skipped("already in progress")
                }
                cancelScheduledRetry()
                lastRestartAttemptMs = SystemClock.elapsedRealtime()
            }
            return result
        }
    }

    fun checkHealth(): HealthResult {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            logw("Watchdog: checkHealth() called on main thread!")
        }

        val now = SystemClock.elapsedRealtime()
        val startTime = healthCheckStartTimeMs.get()
        if (healthCheckInProgress.get()) {
            if (startTime > 0L && (now - startTime) > HEALTH_CHECK_STALE_TIMEOUT_MS) {
                logw("Watchdog: previous health check timed out or hung, releasing stale guard")
                healthCheckInProgress.set(false)
            }
        }

        if (!healthCheckInProgress.compareAndSet(false, true)) {
            val isAlive = try { Shizuku.getBinder()?.isBinderAlive == true } catch (_: Throwable) { false }
            return HealthResult(
                status = HealthStatus.ZOMBIE,
                reason = "health check already in progress or previously hung in transaction",
                healthy = false,
                binderAlive = isAlive
            )
        }

        val generation = healthCheckGeneration.incrementAndGet()
        healthCheckStartTimeMs.set(SystemClock.elapsedRealtime())

        val future = healthCheckExecutor.submit(Callable {
            try {
                healthProbe?.invoke() ?: performDirectHealthCheck()
            } finally {
                if (healthCheckGeneration.get() == generation) {
                    healthCheckStartTimeMs.set(0L)
                    healthCheckInProgress.set(false)
                }
            }
        })

        return try {
            future.get(HEALTH_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            val isAlive = try { Shizuku.getBinder()?.isBinderAlive == true } catch (_: Throwable) { false }
            HealthResult(
                status = HealthStatus.ZOMBIE,
                reason = "binder transaction timed out after ${HEALTH_CHECK_TIMEOUT_MS}ms",
                healthy = false,
                binderAlive = isAlive
            )
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            val isDead = cause is DeadObjectException || try { Shizuku.getBinder()?.isBinderAlive != true } catch (_: Throwable) { true }
            val status = if (isDead) HealthStatus.DEAD else HealthStatus.ZOMBIE
            val isAlive = !isDead && try { Shizuku.getBinder()?.isBinderAlive == true } catch (_: Throwable) { false }
            HealthResult(
                status = status,
                reason = "binder check failed: ${cause.javaClass.simpleName} - ${cause.message}",
                healthy = false,
                binderAlive = isAlive
            )
        } catch (e: InterruptedException) {
            HealthResult(
                status = HealthStatus.DEAD,
                reason = "binder check interrupted",
                healthy = false,
                binderAlive = false
            )
        }
    }

    private fun performDirectHealthCheck(): HealthResult {
        return try {
            val binder = Shizuku.getBinder()
                ?: return HealthResult(HealthStatus.DEAD, "binder is null", healthy = false, binderAlive = false)

            if (!binder.isBinderAlive) {
                return HealthResult(HealthStatus.DEAD, "binder is not alive", healthy = false, binderAlive = false)
            }

            val ping = try {
                Shizuku.pingBinder() && binder.pingBinder()
            } catch (e: Throwable) {
                false
            }
            if (!ping) {
                return HealthResult(HealthStatus.DEAD, "pingBinder() failed", healthy = false, binderAlive = false)
            }

            val version = try {
                Shizuku.getVersion()
            } catch (e: Throwable) {
                val isDead = e is DeadObjectException || !binder.isBinderAlive
                val status = if (isDead) HealthStatus.DEAD else HealthStatus.ZOMBIE
                val isAlive = !isDead && binder.isBinderAlive
                return HealthResult(status, "getVersion failed: ${e.javaClass.simpleName} - ${e.message}", healthy = false, binderAlive = isAlive)
            }
            if (version <= 0) {
                return HealthResult(HealthStatus.ZOMBIE, "bad remote version=$version", healthy = false, binderAlive = binder.isBinderAlive)
            }

            val uid = try {
                Shizuku.getUid()
            } catch (e: Throwable) {
                val isDead = e is DeadObjectException || !binder.isBinderAlive
                val status = if (isDead) HealthStatus.DEAD else HealthStatus.ZOMBIE
                val isAlive = !isDead && binder.isBinderAlive
                return HealthResult(status, "getUid failed: ${e.javaClass.simpleName} - ${e.message}", healthy = false, binderAlive = isAlive)
            }
            if (uid < 0) {
                return HealthResult(HealthStatus.ZOMBIE, "bad remote uid=$uid", healthy = false, binderAlive = binder.isBinderAlive)
            }

            HealthResult(HealthStatus.HEALTHY, "ok version=$version uid=$uid", healthy = true, binderAlive = true)
        } catch (e: Throwable) {
            val isDead = e is DeadObjectException || try { Shizuku.getBinder()?.isBinderAlive != true } catch (_: Throwable) { true }
            val status = if (isDead) HealthStatus.DEAD else HealthStatus.ZOMBIE
            val isAlive = !isDead && try { Shizuku.getBinder()?.isBinderAlive == true } catch (_: Throwable) { false }
            HealthResult(status, "check threw ${e.javaClass.simpleName}: ${e.message}", healthy = false, binderAlive = isAlive)
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
                cancelScheduledRetry()
                return
            }
            is AdmissionResult.Cooldown -> {
                logd("Watchdog: cooldown active (${admission.remainingMs}ms remaining)")
                return
            }
            is AdmissionResult.Admitted -> {
                cancelScheduledRetry()
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
                        cancelScheduledRetry()
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
                    cancelScheduledRetry()
                } else {
                    scheduleRetry(app, newAttempts)
                }
            } catch (e: CancellationException) {
                logd("Watchdog: recovery cancelled")
                throw e
            } catch (t: Throwable) {
                val newAttempts = recoveryAttempts.incrementAndGet()
                logw("Watchdog: recovery failed with exception (attempt $newAttempts/$MAX_RECOVERY_ATTEMPTS): ${t.message}")
                if (newAttempts >= MAX_RECOVERY_ATTEMPTS) {
                    logw("Watchdog: maximum recovery attempts reached")
                    cancelScheduledRetry()
                } else {
                    scheduleRetry(app, newAttempts)
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
