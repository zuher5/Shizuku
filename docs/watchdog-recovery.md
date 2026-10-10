# Shizuku Watchdog & Recovery Architecture

## Overview

This fork of Shizuku includes a hardened watchdog and recovery system that automatically detects server termination or unresponsiveness and attempts graceful restoration while preserving battery life, UI invariants, and system responsiveness.

---

## Architecture Components

1. **`ShizukuStateMachine`**
   - Centralized atomic state holder (`AtomicReference<State>`) tracking:
     - `STARTING`
     - `RUNNING`
     - `STOPPING`
     - `STOPPED`
     - `CRASHED`
   - One-time thread-safe binder listener registration via `AtomicBoolean.compareAndSet`.
   - Distinguishes graceful server stops (`STOPPING -> STOPPED`) from unexpected process terminations (`RUNNING -> CRASHED`).

2. **`WatchdogManager`**
   - Singleton coordinating watchdog policy, user-stop persistence, and restart admission.
   - Listens to sticky binder received/dead events registered once on initialization.
   - Manages expected death deadlines (`AtomicLong` timestamp window) so intentional restarts or shutdowns do not trigger false recovery alerts.
   - Tracks user-requested stops in `SharedPreferences` (`watchdog_user_stop_requested`) and in memory (`AtomicBoolean`) to guarantee recovery remains suppressed until the user explicitly restarts the service.

3. **`WatchdogDecisions`**
   - Pure, deterministic decision engine with zero Android framework dependencies.
   - Evaluates admission preconditions:
     - Enabled watchdog setting
     - User-stop requested flag
     - Starter activity active flag
     - Expected death active flag
     - Launch mode validity (`ROOT` or `ADB`)
     - Retry attempt threshold (maximum 5 attempts)
     - Exponential backoff cooldown (`[15s, 30s, 60s, 120s]`)
     - Active restart in-progress lock

4. **`WatchdogService`**
   - Foreground/background service performing periodic Binder health checks every 30 seconds.
   - Automatically pauses health checks during in-progress restarts, expected death windows, or when the manual starter is active.
   - On unhealthy state, handles zombie Binder instances (attempts graceful stop and waits for stopped state) before triggering recovery.

5. **`AdbStartWorker`**
   - Android `CoroutineWorker` handling wireless and TCP ADB daemon activation.
   - Uses `ExistingWorkPolicy.KEEP` to ensure a secondary watchdog or network event never cancels or interrupts an active worker that is awaiting mDNS discovery or lock screen authorization.
   - Rechecks Binder health immediately upon execution to exit cleanly if Shizuku is already alive.
   - Registers for `ACTION_USER_PRESENT` when the keyguard is locked, safely unregistering receivers and observers across all execution paths (success, timeout, error, or cancellation).

---

## Health Check Bounds and Deadlock Prevention

- **Timeout:** Bounded to 3,000 ms (`HEALTH_CHECK_TIMEOUT_MS`).
- **Thread Pool:** Executed on a dedicated daemon thread pool (`watchdog-health-check`).
- **Single-Flight Guard:** `healthCheckInProgress` (`AtomicBoolean`) prevents thread exhaustion if a hung IPC transaction blocks a worker thread.
- **Classification:**
  - `HEALTHY`: Binder ping succeeds and server IPC returns version successfully.
  - `ZOMBIE`: Binder ping succeeds or is present, but transaction hangs or throws an exception.
  - `DEAD`: Binder is null or dead.

---

## Automated Testing & CI Verification

- **JVM Unit Tests:**
  - `WatchdogDecisionsTest`: Verifies admission rules, backoff schedules, attempt exhaustion, and health result classification deterministically.
  - `ShizukuStateMachineTest`: Verifies state transitions, graceful stops vs crashes, and listener registration/concurrency.
- **Continuous Integration:**
  - `.github/workflows/ci.yml`: Triggers on every push and pull request to `master`, `beta`, and `feature/**` branches, executing `./gradlew testDebugUnitTest :manager:assembleDebug`.
  - `.github/workflows/app.yml`: Restricted release workflow supporting manual debug/release builds with provenance attestation, guarded against unauthorized PR executions.
