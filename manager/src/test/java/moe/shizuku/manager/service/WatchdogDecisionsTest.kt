package moe.shizuku.manager.service

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
            WatchdogManager.HealthStatus.HEALTHY,
            "alive and responding"
        )
        assertTrue(healthy.healthy)
        assertTrue(healthy.binderAlive)
        assertEquals(WatchdogManager.HealthStatus.HEALTHY, healthy.status)

        val zombie = WatchdogManager.HealthResult(
            WatchdogManager.HealthStatus.ZOMBIE,
            "timed out"
        )
        assertFalse(zombie.healthy)
        assertTrue(zombie.binderAlive)
        assertEquals(WatchdogManager.HealthStatus.ZOMBIE, zombie.status)

        val dead = WatchdogManager.HealthResult(
            WatchdogManager.HealthStatus.DEAD,
            "binder null"
        )
        assertFalse(dead.healthy)
        assertFalse(dead.binderAlive)
        assertEquals(WatchdogManager.HealthStatus.DEAD, dead.status)
    }
}
