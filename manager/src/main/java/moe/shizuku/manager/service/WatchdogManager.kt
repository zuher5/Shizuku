package moe.shizuku.manager.service

import android.content.Context
import android.os.SystemClock
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
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ktx.logd
import moe.shizuku.manager.ktx.logi
import moe.shizuku.manager.ktx.logw
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.worker.AdbStartWorker
import rikka.shizuku.Shizuku


/**
 * Owns the watchdog policy: when a restart is allowed, how long to wait
 * between attempts and how many attempts are left.
 *
 * The manager is deliberately free of Android UI concerns. Every decision is
 * taken under [admissionLock] so a binder death, a health check and a manual
 * start cannot race each other into two concurrent restarts.
 */
object WatchdogManager {

    /** How long an intentional stop or restart is expected to take. */
    private const val EXPECTED_DEATH_WINDOW_MS = 30_000L

    /** Minimum delay between two restart attempts. */
    private const val MIN_RESTART_INTERVAL_MS = WatchdogPolicy.MIN_RESTART_INTERVAL_MS

    /** Maximum number of consecutive restart attempts. */
    const val MAX_RECOVERY_ATTEMPTS = WatchdogPolicy.MAX_RECOVERY_ATTEMPTS

    private const val KEY_USER_STOP_REQUESTED = "watchdog_user_stop_requested"

    /** A Binder transaction must not block the caller for longer than this. */
    private const val HEALTH_CHECK_TIMEOUT_MS = 3_000L

    /**
     * The native supervisor respawns a crashed server after a few seconds.
     * The client waits for that window before issuing a restart of its own, so
     * the two never race to start a second server.
     */
    private const val SUPERVISOR_RECOVERY_GRACE_MS = 8_000L

    @Volatile
    var isStarterActive: Boolean = false

    private val initialized = AtomicBoolean(false)
    private val restartInProgress = AtomicBoolean(false)
    private val userStopRequested = AtomicBoolean(false)
    private val recoveryAttempts = AtomicInteger(0)
    private val expectedDeathDeadlineMillis = AtomicLong(0L)
    private val lastRestartAttemptMs = AtomicLong(0L)

    private val admissionLock = Any()
    private val watchdogJob = SupervisorJob()
    private val watchdogScope = CoroutineScope(watchdogJob + Dispatchers.IO)

    private val healthCheckInProgress = AtomicBoolean(false)
    private val healthCheckExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "watchdog-health-check").apply { isDaemon = true }
    }

    @Volatile
    private var appContext: Context? = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        logi("Watchdog: binder received")
        expectedDeathDeadlineMillis.set(0L)
        recoveryAttempts.set(0)
        clearUserStopRequest()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        logw("Watchdog: binder died")
        val context = appContext
        if (context != null) {
            onServiceDied(context)
        }
    }

    fun init(context: Context) {
        val app = context.applicationContext
        synchronized(admissionLock) {
            if (appContext == null) {
                appContext = app
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

    fun isEnabled(): Boolean = ShizukuSettings.getWatchdog()

    fun shouldRunService(): Boolean = isEnabled() && !isUserStopRequested()

    /**
     * True while an intentional stop or restart is in flight. The window
     * expires on its own, so a missed callback can never block recovery
     * forever.
     */
    fun isExpectingDeathActive(): Boolean {
        if (isStarterActive) return true
        val deadline = expectedDeathDeadlineMillis.get()
        if (deadline == 0L) return false
        if (SystemClock.elapsedRealtime() <= deadline) return true
        expectedDeathDeadlineMillis.compareAndSet(deadline, 0L)
        return false
    }

    /** Marks the next binder death as expected (intentional stop or restart). */
    fun expectDeath() {
        expectedDeathDeadlineMillis.set(SystemClock.elapsedRealtime() + EXPECTED_DEATH_WINDOW_MS)
    }

    fun clearExpectedDeath() {
        expectedDeathDeadlineMillis.set(0L)
    }

    fun isUserStopRequested(): Boolean {
        if (initialized.get()) return userStopRequested.get()
        return ShizukuSettings.getPreferences()?.getBoolean(KEY_USER_STOP_REQUESTED, false) ?: false
    }

    fun setUserStopRequested(value: Boolean) {
        userStopRequested.set(value)
        ShizukuSettings.getPreferences()?.edit()
            ?.putBoolean(KEY_USER_STOP_REQUESTED, value)
            ?.apply()
    }

    fun clearUserStopRequest(context: Context? = null) {
        setUserStopRequested(false)
        clearExpectedDeath()
        recoveryAttempts.set(0)
        context?.let { reconcileService(it) }
    }

    fun reconcileService(context: Context) {
        val app = context.applicationContext
        synchronized(admissionLock) {
            if (appContext == null) {
                appContext = app
            }
        }
        if (shouldRunService()) {
            WatchdogService.start(app)
        } else {
            WatchdogService.stop(app)
        }
    }

    fun isRestartInProgress(): Boolean = restartInProgress.get()

    fun getRecoveryAttempts(): Int = recoveryAttempts.get()

    fun getRequiredCooldown(attempts: Int): Long = WatchdogPolicy.getRequiredCooldown(attempts)

    /**
     * Decides whether a restart may start right now.
     * Every precondition is checked under one lock, so no two callers can be
     * admitted at the same time.
     */
    fun tryAdmitRestart(): WatchdogPolicy.AdmissionResult {
        synchronized(admissionLock) {
            val result = WatchdogPolicy.evaluateAdmission(
                isEnabled = isEnabled(),
                isUserStopRequested = isUserStopRequested(),
                isStarterActive = isStarterActive,
                isExpectingDeathActive = isExpectingDeathActive(),
                launchMode = ShizukuSettings.getLastLaunchMode(),
                attempts = recoveryAttempts.get(),
                now = SystemClock.elapsedRealtime(),
                lastRestartAttemptMs = lastRestartAttemptMs.get(),
                isRestartInProgress = restartInProgress.get(),
                unknownLaunchMode = ShizukuSettings.LaunchMethod.UNKNOWN
            )
            if (result is WatchdogPolicy.AdmissionResult.Admitted) {
                if (!restartInProgress.compareAndSet(false, true)) {
                    return WatchdogPolicy.AdmissionResult.Skipped("already in progress")
                }
                lastRestartAttemptMs.set(SystemClock.elapsedRealtime())
            }
            return result
        }
    }

    private fun consumeExpectedDeath(): Boolean {
        val deadline = expectedDeathDeadlineMillis.getAndSet(0L)
        if (deadline == 0L) return false
        if (SystemClock.elapsedRealtime() <= deadline) return true
        logd("Watchdog: ignoring stale expected-death window")
        return false
    }

    fun onServiceDied(context: Context) {
        val app = context.applicationContext
        synchronized(admissionLock) {
            if (appContext == null) {
                appContext = app
            }
        }

        if (isStarterActive) {
            logi("Watchdog: starter active, suppressing restart")
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
        if (!isEnabled()) {
            logi("Watchdog: disabled")
            return
        }

        watchdogScope.launch {
            // Give the native supervisor time to respawn the server before the
            // client starts one of its own.
            kotlinx.coroutines.delay(SUPERVISOR_RECOVERY_GRACE_MS)
            if (Shizuku.pingBinder() || isStarterActive || isUserStopRequested()) {
                logi("Watchdog: server already recovered by the supervisor")
                return@launch
            }
            attemptRestart(app)
        }
    }

    /**
     * Starts the server again and verifies that it came back.
     * A restart only counts as successful when a health check passes.
     */
    fun attemptRestart(context: Context) {
        val app = context.applicationContext
        synchronized(admissionLock) {
            if (appContext == null) {
                appContext = app
            }
        }

        when (val admission = tryAdmitRestart()) {
            is WatchdogPolicy.AdmissionResult.Skipped -> {
                logd("Watchdog: recovery skipped (${admission.reason})")
                return
            }
            is WatchdogPolicy.AdmissionResult.Exhausted -> {
                logw("Watchdog: maximum recovery attempts reached")
                return
            }
            is WatchdogPolicy.AdmissionResult.Cooldown -> {
                logd("Watchdog: cooldown active (${admission.remainingMs}ms remaining)")
                return
            }
            is WatchdogPolicy.AdmissionResult.Admitted -> {
                val attemptNum = admission.attempts + 1
                watchdogScope.launch {
                    try {
                        logi("Watchdog: recovery started (mode=${admission.mode}, attempt=$attemptNum/$MAX_RECOVERY_ATTEMPTS, delay=${admission.delayMs}ms)")

                        when (admission.mode) {
                            ShizukuSettings.LaunchMethod.ROOT -> restartRoot()
                            ShizukuSettings.LaunchMethod.ADB -> restartAdb(app)
                            else -> logd("Watchdog: unknown launch mode ${admission.mode}")
                        }

                        if (ShizukuStateMachine.awaitRunning(15_000L)) {
                            val health = checkHealth()
                            if (health.healthy) {
                                logi("Watchdog: recovery succeeded")
                                recoveryAttempts.set(0)
                                return@launch
                            }
                            logw("Watchdog: unhealthy after restart (${health.reason})")
                        } else {
                            logw("Watchdog: recovery failed, no binder in time")
                        }

                        val newAttempts = recoveryAttempts.incrementAndGet()
                        logw("Watchdog: recovery failed (attempt $newAttempts/$MAX_RECOVERY_ATTEMPTS)")
                        if (newAttempts >= MAX_RECOVERY_ATTEMPTS) {
                            logw("Watchdog: giving up, maximum recovery attempts reached")
                        }
                    } catch (e: CancellationException) {
                        logd("Watchdog: recovery cancelled")
                        throw e
                    } catch (t: Throwable) {
                        val newAttempts = recoveryAttempts.incrementAndGet()
                        logw("Watchdog: recovery failed with exception (attempt $newAttempts/$MAX_RECOVERY_ATTEMPTS): ${t.message}")
                    } finally {
                        restartInProgress.set(false)
                    }
                }
            }
        }
    }

    private fun restartRoot() {
        try {
            if (!Shell.getShell().isRoot) {
                Shell.getCachedShell()?.close()
            }
            if (Shell.getShell().isRoot) {
                ShizukuStateMachine.onStartRequested()
                Shell.cmd(Starter.internalCommand).exec()
            } else {
                logw("Watchdog: cannot restart via root, device is not rooted")
            }
        } catch (e: Exception) {
            logw("Watchdog: root restart failed: ${e.message}")
            ShizukuStateMachine.onStartFailed()
        }
    }

    private fun restartAdb(context: Context) {
        AdbStartWorker.enqueueIfIdle(context.applicationContext)
    }

    /**
     * Checks whether the server is alive and responsive.
     *
     * The transaction runs on a dedicated thread with a hard timeout: a hung
     * Binder call must neither block the caller nor pile up threads.
     */
    fun checkHealth(): WatchdogPolicy.HealthResult {
        if (!healthCheckInProgress.compareAndSet(false, true)) {
            return WatchdogPolicy.HealthResult(WatchdogPolicy.HealthStatus.ZOMBIE, "health check already in progress")
        }

        val future = healthCheckExecutor.submit(Callable {
            try {
                performHealthCheck()
            } finally {
                healthCheckInProgress.set(false)
            }
        })

        return try {
            future.get(HEALTH_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            WatchdogPolicy.HealthResult(WatchdogPolicy.HealthStatus.ZOMBIE, "binder transaction timed out after ${HEALTH_CHECK_TIMEOUT_MS}ms")
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            WatchdogPolicy.HealthResult(WatchdogPolicy.HealthStatus.DEAD, "binder check failed: ${cause.javaClass.simpleName} - ${cause.message}")
        } catch (e: InterruptedException) {
            WatchdogPolicy.HealthResult(WatchdogPolicy.HealthStatus.DEAD, "binder check interrupted")
        }
    }

    private fun performHealthCheck(): WatchdogPolicy.HealthResult {
        return try {
            val binder = Shizuku.getBinder()
                ?: return WatchdogPolicy.HealthResult(WatchdogPolicy.HealthStatus.DEAD, "binder is null")

            if (!binder.isBinderAlive) {
                return WatchdogPolicy.HealthResult(WatchdogPolicy.HealthStatus.DEAD, "binder is not alive")
            }

            val ping = try {
                Shizuku.pingBinder() && binder.pingBinder()
            } catch (e: Throwable) {
                false
            }
            if (!ping) {
                return WatchdogPolicy.HealthResult(WatchdogPolicy.HealthStatus.DEAD, "pingBinder() failed")
            }

            val version = try {
                Shizuku.getVersion()
            } catch (e: Throwable) {
                val dead = e is android.os.DeadObjectException || !binder.isBinderAlive
                return WatchdogPolicy.HealthResult(
                    if (dead) WatchdogPolicy.HealthStatus.DEAD else WatchdogPolicy.HealthStatus.ZOMBIE,
                    "getVersion failed: ${e.javaClass.simpleName} - ${e.message}"
                )
            }
            if (version <= 0) {
                return WatchdogPolicy.HealthResult(WatchdogPolicy.HealthStatus.ZOMBIE, "bad remote version=$version")
            }

            val uid = try {
                Shizuku.getUid()
            } catch (e: Throwable) {
                val dead = e is android.os.DeadObjectException || !binder.isBinderAlive
                return WatchdogPolicy.HealthResult(
                    if (dead) WatchdogPolicy.HealthStatus.DEAD else WatchdogPolicy.HealthStatus.ZOMBIE,
                    "getUid failed: ${e.javaClass.simpleName} - ${e.message}"
                )
            }
            if (uid < 0) {
                return WatchdogPolicy.HealthResult(WatchdogPolicy.HealthStatus.ZOMBIE, "bad remote uid=$uid")
            }

            WatchdogPolicy.HealthResult(WatchdogPolicy.HealthStatus.HEALTHY, "ok version=$version uid=$uid")
        } catch (e: Throwable) {
            val alive = try { Shizuku.pingBinder() } catch (t: Throwable) { false }
            WatchdogPolicy.HealthResult(
                if (alive) WatchdogPolicy.HealthStatus.ZOMBIE else WatchdogPolicy.HealthStatus.DEAD,
                "check threw ${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    /**
     * Asks the server to stop. The binder death that follows is expected, so
     * it is marked as such to avoid a restart loop.
     */
    fun requestStopServer(context: Context? = null, userInitiated: Boolean = true): Throwable? {
        if (userInitiated) {
            setUserStopRequested(true)
            context?.let { reconcileService(it) }
        }
        expectDeath()
        ShizukuStateMachine.onStopRequested()
        return try {
            Shizuku.exit()
            null
        } catch (e: Throwable) {
            logd("Watchdog: stop failed: ${e.message}")
            clearExpectedDeath()
            ShizukuStateMachine.update()
            e
        }
    }
}
