package moe.shizuku.manager.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchdogPolicyTest {

    private val unknownMode = 0
    private val adbMode = 1

    private fun evaluate(
        isEnabled: Boolean = true,
        isUserStopRequested: Boolean = false,
        isStarterActive: Boolean = false,
        isExpectingDeathActive: Boolean = false,
        launchMode: Int = adbMode,
        attempts: Int = 0,
        now: Long = 100_000L,
        lastRestartAttemptMs: Long = 0L,
        isRestartInProgress: Boolean = false
    ) = WatchdogPolicy.evaluateAdmission(
        isEnabled = isEnabled,
        isUserStopRequested = isUserStopRequested,
        isStarterActive = isStarterActive,
        isExpectingDeathActive = isExpectingDeathActive,
        launchMode = launchMode,
        attempts = attempts,
        now = now,
        lastRestartAttemptMs = lastRestartAttemptMs,
        isRestartInProgress = isRestartInProgress,
        unknownLaunchMode = unknownMode
    )

    @Test
    fun disabledWatchdogSuppressesRecovery() {
        val result = evaluate(isEnabled = false)
        assertTrue(result is WatchdogPolicy.AdmissionResult.Skipped)
        assertEquals("watchdog disabled", (result as WatchdogPolicy.AdmissionResult.Skipped).reason)
    }

    @Test
    fun userStopSuppressesRecovery() {
        val result = evaluate(isUserStopRequested = true)
        assertTrue(result is WatchdogPolicy.AdmissionResult.Skipped)
        assertEquals("user stop requested", (result as WatchdogPolicy.AdmissionResult.Skipped).reason)
    }

    @Test
    fun activeStarterSuppressesRecovery() {
        val result = evaluate(isStarterActive = true)
        assertTrue(result is WatchdogPolicy.AdmissionResult.Skipped)
        assertEquals("starter active", (result as WatchdogPolicy.AdmissionResult.Skipped).reason)
    }

    @Test
    fun expectedDeathSuppressesRecovery() {
        val result = evaluate(isExpectingDeathActive = true)
        assertTrue(result is WatchdogPolicy.AdmissionResult.Skipped)
        assertEquals("death expected", (result as WatchdogPolicy.AdmissionResult.Skipped).reason)
    }

    @Test
    fun unknownLaunchModeSuppressesRecovery() {
        val result = evaluate(launchMode = unknownMode)
        assertTrue(result is WatchdogPolicy.AdmissionResult.Skipped)
        assertEquals("UNKNOWN launch mode", (result as WatchdogPolicy.AdmissionResult.Skipped).reason)
    }

    @Test
    fun maximumAttemptsExhaustsRecovery() {
        val result = evaluate(attempts = WatchdogPolicy.MAX_RECOVERY_ATTEMPTS, lastRestartAttemptMs = 1L)
        assertTrue(result is WatchdogPolicy.AdmissionResult.Exhausted)
    }

    @Test
    fun cooldownBlocksRestartUntilItElapsed() {
        // 10s since the last attempt, the first cooldown is 15s.
        val result = evaluate(now = 100_000L, lastRestartAttemptMs = 90_000L)
        assertTrue(result is WatchdogPolicy.AdmissionResult.Cooldown)
        assertEquals(5_000L, (result as WatchdogPolicy.AdmissionResult.Cooldown).remainingMs)
    }

    @Test
    fun elapsedCooldownAdmitsRestart() {
        // 20s since the last attempt, the first cooldown is 15s.
        val result = evaluate(now = 120_000L, lastRestartAttemptMs = 100_000L)
        assertTrue(result is WatchdogPolicy.AdmissionResult.Admitted)
        val admitted = result as WatchdogPolicy.AdmissionResult.Admitted
        assertEquals(adbMode, admitted.mode)
        assertEquals(0, admitted.attempts)
        assertEquals(15_000L, admitted.delayMs)
    }

    @Test
    fun restartInProgressBlocksASecondRestart() {
        // The cooldown has already elapsed, so the in-progress lock is the
        // only thing that can veto the restart.
        val result = evaluate(now = 200_000L, lastRestartAttemptMs = 100_000L, isRestartInProgress = true)
        assertTrue(result is WatchdogPolicy.AdmissionResult.Skipped)
        assertEquals("already in progress", (result as WatchdogPolicy.AdmissionResult.Skipped).reason)
    }

    @Test
    fun firstAttemptIsNeverCooledDown() {
        val result = evaluate(now = 1L, lastRestartAttemptMs = 0L)
        assertTrue(result is WatchdogPolicy.AdmissionResult.Admitted)
    }

    @Test
    fun backoffGrowsWithEveryFailedAttempt() {
        assertEquals(15_000L, WatchdogPolicy.getRequiredCooldown(0))
        assertEquals(15_000L, WatchdogPolicy.getRequiredCooldown(1))
        assertEquals(30_000L, WatchdogPolicy.getRequiredCooldown(2))
        assertEquals(60_000L, WatchdogPolicy.getRequiredCooldown(3))
        assertEquals(120_000L, WatchdogPolicy.getRequiredCooldown(4))
        assertEquals(120_000L, WatchdogPolicy.getRequiredCooldown(9))
    }

    @Test
    fun healthResultClassification() {
        val healthy = WatchdogPolicy.HealthResult(WatchdogPolicy.HealthStatus.HEALTHY, "ok")
        assertTrue(healthy.healthy)
        assertTrue(healthy.binderAlive)

        val zombie = WatchdogPolicy.HealthResult(WatchdogPolicy.HealthStatus.ZOMBIE, "timeout")
        assertFalse(zombie.healthy)
        assertTrue(zombie.binderAlive)

        val dead = WatchdogPolicy.HealthResult(WatchdogPolicy.HealthStatus.DEAD, "no binder")
        assertFalse(dead.healthy)
        assertFalse(dead.binderAlive)
    }
}
