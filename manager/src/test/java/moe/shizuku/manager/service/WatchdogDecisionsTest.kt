package moe.shizuku.manager.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import moe.shizuku.manager.ShizukuSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchdogDecisionsTest {

    @Test
    fun testDisabledWatchdogSuppressesRecovery() {
        val result = WatchdogDecisions.evaluateAdmission(
            isEnabled = false,
            isUserStopRequested = false,
            isStarterActive = false,
            isExpectingDeathActive = false,
            launchMode = ShizukuSettings.LaunchMethod.ADB,
            attempts = 0,
            now = 100_000L,
            lastRestartAttemptMs = 0L,
            isRestartInProgress = false
        )
        assertTrue(result is WatchdogManager.AdmissionResult.Skipped)
        assertEquals("watchdog disabled", (result as WatchdogManager.AdmissionResult.Skipped).reason)
    }

    @Test
    fun testUserStopRequestedSuppressesRecovery() {
        val result = WatchdogDecisions.evaluateAdmission(
            isEnabled = true,
            isUserStopRequested = true,
            isStarterActive = false,
            isExpectingDeathActive = false,
            launchMode = ShizukuSettings.LaunchMethod.ROOT,
            attempts = 0,
            now = 100_000L,
            lastRestartAttemptMs = 0L,
            isRestartInProgress = false
        )
        assertTrue(result is WatchdogManager.AdmissionResult.Skipped)
        assertEquals("user stop requested", (result as WatchdogManager.AdmissionResult.Skipped).reason)
    }

    @Test
    fun testStarterActiveSuppressesRecovery() {
        val result = WatchdogDecisions.evaluateAdmission(
            isEnabled = true,
            isUserStopRequested = false,
            isStarterActive = true,
            isExpectingDeathActive = false,
            launchMode = ShizukuSettings.LaunchMethod.ADB,
            attempts = 0,
            now = 100_000L,
            lastRestartAttemptMs = 0L,
            isRestartInProgress = false
        )
        assertTrue(result is WatchdogManager.AdmissionResult.Skipped)
        assertEquals("starter active", (result as WatchdogManager.AdmissionResult.Skipped).reason)
    }

    @Test
    fun testExpectedDeathSuppressesRecovery() {
        val result = WatchdogDecisions.evaluateAdmission(
            isEnabled = true,
            isUserStopRequested = false,
            isStarterActive = false,
            isExpectingDeathActive = true,
            launchMode = ShizukuSettings.LaunchMethod.ADB,
            attempts = 0,
            now = 100_000L,
            lastRestartAttemptMs = 0L,
            isRestartInProgress = false
        )
        assertTrue(result is WatchdogManager.AdmissionResult.Skipped)
        assertEquals("death expected", (result as WatchdogManager.AdmissionResult.Skipped).reason)
    }

    @Test
    fun testUnknownLaunchModeSuppressesRecovery() {
        val result = WatchdogDecisions.evaluateAdmission(
            isEnabled = true,
            isUserStopRequested = false,
            isStarterActive = false,
            isExpectingDeathActive = false,
            launchMode = ShizukuSettings.LaunchMethod.UNKNOWN,
            attempts = 0,
            now = 100_000L,
            lastRestartAttemptMs = 0L,
            isRestartInProgress = false
        )
        assertTrue(result is WatchdogManager.AdmissionResult.Skipped)
        assertEquals("UNKNOWN launch mode", (result as WatchdogManager.AdmissionResult.Skipped).reason)
    }

    @Test
    fun testMaxRecoveryAttemptsExhaustsRecovery() {
        val result = WatchdogDecisions.evaluateAdmission(
            isEnabled = true,
            isUserStopRequested = false,
            isStarterActive = false,
            isExpectingDeathActive = false,
            launchMode = ShizukuSettings.LaunchMethod.ADB,
            attempts = 5,
            now = 500_000L,
            lastRestartAttemptMs = 100_000L,
            isRestartInProgress = false
        )
        assertTrue(result is WatchdogManager.AdmissionResult.Exhausted)
    }

    @Test
    fun testCooldownPreventsPrematureRestart() {
        val now = 100_000L
        val lastAttempt = 90_000L // 10s ago, required cooldown is 15s (attempts = 0)
        val result = WatchdogDecisions.evaluateAdmission(
            isEnabled = true,
            isUserStopRequested = false,
            isStarterActive = false,
            isExpectingDeathActive = false,
            launchMode = ShizukuSettings.LaunchMethod.ADB,
            attempts = 0,
            now = now,
            lastRestartAttemptMs = lastAttempt,
            isRestartInProgress = false
        )
        assertTrue(result is WatchdogManager.AdmissionResult.Cooldown)
        assertEquals(5_000L, (result as WatchdogManager.AdmissionResult.Cooldown).remainingMs)
    }

    @Test
    fun testCooldownExpiredAllowsRestart() {
        val now = 120_000L
        val lastAttempt = 100_000L // 20s ago, required cooldown is 15s (attempts = 0)
        val result = WatchdogDecisions.evaluateAdmission(
            isEnabled = true,
            isUserStopRequested = false,
            isStarterActive = false,
            isExpectingDeathActive = false,
            launchMode = ShizukuSettings.LaunchMethod.ADB,
            attempts = 0,
            now = now,
            lastRestartAttemptMs = lastAttempt,
            isRestartInProgress = false
        )
        assertTrue(result is WatchdogManager.AdmissionResult.Admitted)
        val admitted = result as WatchdogManager.AdmissionResult.Admitted
        assertEquals(ShizukuSettings.LaunchMethod.ADB, admitted.mode)
        assertEquals(0, admitted.attempts)
        assertEquals(15_000L, admitted.delayMs)
    }

    @Test
    fun testRestartAlreadyInProgressSuppressesRestart() {
        val result = WatchdogDecisions.evaluateAdmission(
            isEnabled = true,
            isUserStopRequested = false,
            isStarterActive = false,
            isExpectingDeathActive = false,
            launchMode = ShizukuSettings.LaunchMethod.ADB,
            attempts = 0,
            now = 200_000L,
            lastRestartAttemptMs = 100_000L,
            isRestartInProgress = true
        )
        assertTrue(result is WatchdogManager.AdmissionResult.Skipped)
        assertEquals("already in progress", (result as WatchdogManager.AdmissionResult.Skipped).reason)
    }

    @Test
    fun testBackoffDelayProgression() {
        assertEquals(15_000L, WatchdogDecisions.getRequiredCooldown(0))
        assertEquals(15_000L, WatchdogDecisions.getRequiredCooldown(1))
        assertEquals(30_000L, WatchdogDecisions.getRequiredCooldown(2))
        assertEquals(60_000L, WatchdogDecisions.getRequiredCooldown(3))
        assertEquals(120_000L, WatchdogDecisions.getRequiredCooldown(4))
        assertEquals(120_000L, WatchdogDecisions.getRequiredCooldown(5))
    }

    @Test
    fun testHealthResultClassification() {
        val healthy = WatchdogManager.HealthResult(
            status = WatchdogManager.HealthStatus.HEALTHY,
            reason = "alive and responding",
            healthy = true,
            binderAlive = true
        )
        assertTrue(healthy.healthy)
        assertTrue(healthy.binderAlive)
        assertEquals(WatchdogManager.HealthStatus.HEALTHY, healthy.status)

        // Zombie with alive proxy
        val zombieAlive = WatchdogManager.HealthResult(
            status = WatchdogManager.HealthStatus.ZOMBIE,
            reason = "timed out",
            healthy = false,
            binderAlive = true
        )
        assertFalse(zombieAlive.healthy)
        assertTrue(zombieAlive.binderAlive)
        assertEquals(WatchdogManager.HealthStatus.ZOMBIE, zombieAlive.status)

        // Zombie where proxy is not alive (no unneeded stop-server wait)
        val zombieDead = WatchdogManager.HealthResult(
            status = WatchdogManager.HealthStatus.ZOMBIE,
            reason = "getVersion threw DeadObjectException",
            healthy = false,
            binderAlive = false
        )
        assertFalse(zombieDead.healthy)
        assertFalse(zombieDead.binderAlive)
        assertEquals(WatchdogManager.HealthStatus.ZOMBIE, zombieDead.status)

        val dead = WatchdogManager.HealthResult(
            status = WatchdogManager.HealthStatus.DEAD,
            reason = "binder null",
            healthy = false,
            binderAlive = false
        )
        assertFalse(dead.healthy)
        assertFalse(dead.binderAlive)
        assertEquals(WatchdogManager.HealthStatus.DEAD, dead.status)
    }

    @Test
    fun testResetHealthCheckStateClearsGuard() {
        WatchdogManager.resetHealthCheckState()
        // Calling reset clears the in-progress flag and resets start time
        // If a test probe is set, checkHealth should admit the check without being blocked
        WatchdogManager.healthProbe = {
            WatchdogManager.HealthResult(
                status = WatchdogManager.HealthStatus.HEALTHY,
                reason = "probe healthy",
                healthy = true,
                binderAlive = true
            )
        }
        try {
            val result = WatchdogManager.checkHealth()
            assertTrue(result.healthy)
            assertTrue(result.binderAlive)
            assertEquals("probe healthy", result.reason)
        } finally {
            WatchdogManager.healthProbe = null
            WatchdogManager.resetHealthCheckState()
        }
    }

    @Test
    fun testCancelScheduledRetrySafeWhenNoJob() {
        // Calling cancelScheduledRetry when no job is active should not throw
        WatchdogManager.cancelScheduledRetry()
    }

    @Test
    fun testHungBinderTaskLimitPreventsUnboundedExecution() {
        try {
            // Simulate that 2 tasks are currently hung in transaction
            WatchdogManager.activeHealthCheckCount.set(2)
            val result = WatchdogManager.checkHealth()
            assertFalse(result.healthy)
            assertEquals(WatchdogManager.HealthStatus.ZOMBIE, result.status)
            assertTrue(result.reason.contains("health check task limit reached"))
            // Active count must not have grown
            assertEquals(2, WatchdogManager.activeHealthCheckCount.get())
        } finally {
            WatchdogManager.activeHealthCheckCount.set(0)
            WatchdogManager.resetHealthCheckState()
        }
    }

    @Test
    fun testHungProbeTimesOutAndCapsTaskCount() {
        val latch = CountDownLatch(1)
        WatchdogManager.healthProbe = {
            latch.await(5, TimeUnit.SECONDS)
            WatchdogManager.HealthResult(
                status = WatchdogManager.HealthStatus.HEALTHY,
                reason = "delayed probe",
                healthy = true,
                binderAlive = true
            )
        }
        try {
            // First call will timeout after 3s and return ZOMBIE
            val result = WatchdogManager.checkHealth()
            assertFalse(result.healthy)
            assertEquals(WatchdogManager.HealthStatus.ZOMBIE, result.status)
            assertTrue(WatchdogManager.activeHealthCheckCount.get() <= 2)
        } finally {
            latch.countDown()
            WatchdogManager.healthProbe = null
            WatchdogManager.resetHealthCheckState()
            WatchdogManager.activeHealthCheckCount.set(0)
        }
    }

    @Test
    fun testUserStopCancelsActiveRecoveryAndClearsRestartState() {
        try {
            WatchdogManager.setUserStopRequested(false)
            assertFalse(WatchdogManager.isUserStopRequested())

            // Requesting user stop should cancel any active recovery and retries
            WatchdogManager.setUserStopRequested(true)
            assertTrue(WatchdogManager.isUserStopRequested())
            assertFalse(WatchdogManager.isRestartInProgress())
            assertEquals(null, WatchdogManager.activeRecoveryJob)
        } finally {
            WatchdogManager.setUserStopRequested(false)
        }
    }

    @Test
    fun testConcurrentRecoveryAdmissionAllowsOnlyOne() {
        val admission1 = WatchdogDecisions.evaluateAdmission(
            isEnabled = true,
            isUserStopRequested = false,
            isStarterActive = false,
            isExpectingDeathActive = false,
            launchMode = ShizukuSettings.LaunchMethod.ADB,
            attempts = 0,
            now = 100_000L,
            lastRestartAttemptMs = 0L,
            isRestartInProgress = false
        )
        assertTrue(admission1 is WatchdogManager.AdmissionResult.Admitted)

        val admission2 = WatchdogDecisions.evaluateAdmission(
            isEnabled = true,
            isUserStopRequested = false,
            isStarterActive = false,
            isExpectingDeathActive = false,
            launchMode = ShizukuSettings.LaunchMethod.ADB,
            attempts = 0,
            now = 100_000L,
            lastRestartAttemptMs = 0L,
            isRestartInProgress = true
        )
        assertTrue(admission2 is WatchdogManager.AdmissionResult.Skipped)
        assertEquals("already in progress", (admission2 as WatchdogManager.AdmissionResult.Skipped).reason)
    }

    @Test
    fun testRecoveryAttemptExhaustedAboveMax() {
        val result = WatchdogDecisions.evaluateAdmission(
            isEnabled = true,
            isUserStopRequested = false,
            isStarterActive = false,
            isExpectingDeathActive = false,
            launchMode = ShizukuSettings.LaunchMethod.ROOT,
            attempts = WatchdogDecisions.MAX_RECOVERY_ATTEMPTS + 1,
            now = 200_000L,
            lastRestartAttemptMs = 100_000L,
            isRestartInProgress = false
        )
        assertTrue(result is WatchdogManager.AdmissionResult.Exhausted)
    }
}
