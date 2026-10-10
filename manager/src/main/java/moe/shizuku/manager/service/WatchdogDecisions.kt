package moe.shizuku.manager.service

import moe.shizuku.manager.ShizukuSettings

object WatchdogDecisions {
    const val MIN_RESTART_INTERVAL_MS = 15_000L
    const val MAX_RECOVERY_ATTEMPTS = 5
    val BACKOFF_DELAYS_MS = longArrayOf(15_000L, 30_000L, 60_000L, 120_000L)

    fun getRequiredCooldown(attempts: Int): Long {
        if (attempts <= 0) return MIN_RESTART_INTERVAL_MS
        val index = (attempts - 1).coerceIn(0, BACKOFF_DELAYS_MS.lastIndex)
        return BACKOFF_DELAYS_MS[index]
    }

    fun evaluateAdmission(
        isEnabled: Boolean,
        isUserStopRequested: Boolean,
        isStarterActive: Boolean,
        isExpectingDeathActive: Boolean,
        launchMode: Int,
        attempts: Int,
        now: Long,
        lastRestartAttemptMs: Long,
        isRestartInProgress: Boolean
    ): WatchdogManager.AdmissionResult {
        if (!isEnabled) {
            return WatchdogManager.AdmissionResult.Skipped("watchdog disabled")
        }
        if (isUserStopRequested) {
            return WatchdogManager.AdmissionResult.Skipped("user stop requested")
        }
        if (isStarterActive) {
            return WatchdogManager.AdmissionResult.Skipped("starter active")
        }
        if (isExpectingDeathActive) {
            return WatchdogManager.AdmissionResult.Skipped("death expected")
        }
        if (launchMode == ShizukuSettings.LaunchMethod.UNKNOWN) {
            return WatchdogManager.AdmissionResult.Skipped("UNKNOWN launch mode")
        }
        if (attempts >= MAX_RECOVERY_ATTEMPTS) {
            return WatchdogManager.AdmissionResult.Exhausted
        }
        val requiredCooldown = getRequiredCooldown(attempts)
        val elapsed = now - lastRestartAttemptMs
        if (lastRestartAttemptMs > 0L && elapsed < requiredCooldown) {
            return WatchdogManager.AdmissionResult.Cooldown(requiredCooldown - elapsed)
        }
        if (isRestartInProgress) {
            return WatchdogManager.AdmissionResult.Skipped("already in progress")
        }
        return WatchdogManager.AdmissionResult.Admitted(launchMode, attempts, requiredCooldown)
    }
}
