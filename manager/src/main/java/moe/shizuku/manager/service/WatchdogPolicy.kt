package moe.shizuku.manager.service

/**
 * The watchdog decision rules, free of any Android dependency so they can be
 * unit tested deterministically.
 *
 * [WatchdogManager] owns the state and the side effects; this object owns the
 * rules.
 */
object WatchdogPolicy {

    /** Minimum delay between two restart attempts. */
    const val MIN_RESTART_INTERVAL_MS = 15_000L

    /** Maximum number of consecutive restart attempts. */
    const val MAX_RECOVERY_ATTEMPTS = 5

    /** Backoff schedule, indexed by the number of failed attempts. */
    val BACKOFF_DELAYS_MS = longArrayOf(15_000L, 30_000L, 60_000L, 120_000L)

    enum class HealthStatus {
        HEALTHY,
        ZOMBIE,
        DEAD
    }

    data class HealthResult(
        val status: HealthStatus,
        val reason: String
    ) {
        val healthy: Boolean get() = status == HealthStatus.HEALTHY
        val binderAlive: Boolean get() = status != HealthStatus.DEAD
    }

    sealed class AdmissionResult {
        data class Admitted(val mode: Int, val attempts: Int, val delayMs: Long) : AdmissionResult()
        data class Cooldown(val remainingMs: Long) : AdmissionResult()
        data class Skipped(val reason: String) : AdmissionResult()
        object Exhausted : AdmissionResult()
    }

    /** The delay that must elapse before the next attempt. */
    fun getRequiredCooldown(attempts: Int): Long {
        if (attempts <= 0) return MIN_RESTART_INTERVAL_MS
        val index = (attempts - 1).coerceIn(0, BACKOFF_DELAYS_MS.lastIndex)
        return BACKOFF_DELAYS_MS[index]
    }

    /**
     * Decides whether a restart may start right now.
     *
     * The checks are ordered from the cheapest and most specific to the most
     * expensive, and every one of them can veto the restart.
     */
    fun evaluateAdmission(
        isEnabled: Boolean,
        isUserStopRequested: Boolean,
        isStarterActive: Boolean,
        isExpectingDeathActive: Boolean,
        launchMode: Int,
        attempts: Int,
        now: Long,
        lastRestartAttemptMs: Long,
        isRestartInProgress: Boolean,
        unknownLaunchMode: Int
    ): AdmissionResult {
        if (!isEnabled) {
            return AdmissionResult.Skipped("watchdog disabled")
        }
        if (isUserStopRequested) {
            return AdmissionResult.Skipped("user stop requested")
        }
        if (isStarterActive) {
            return AdmissionResult.Skipped("starter active")
        }
        if (isExpectingDeathActive) {
            return AdmissionResult.Skipped("death expected")
        }
        if (launchMode == unknownLaunchMode) {
            return AdmissionResult.Skipped("UNKNOWN launch mode")
        }
        if (attempts >= MAX_RECOVERY_ATTEMPTS) {
            return AdmissionResult.Exhausted
        }
        val requiredCooldown = getRequiredCooldown(attempts)
        val elapsed = now - lastRestartAttemptMs
        if (lastRestartAttemptMs > 0L && elapsed < requiredCooldown) {
            return AdmissionResult.Cooldown(requiredCooldown - elapsed)
        }
        if (isRestartInProgress) {
            return AdmissionResult.Skipped("already in progress")
        }
        return AdmissionResult.Admitted(launchMode, attempts, requiredCooldown)
    }
}
