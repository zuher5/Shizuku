# Manual and Instrumentation Testing Notes

This document describes manual and on-device test scenarios for verifying Shizuku watchdog and reliability features that depend on real Android hardware, Binder IPC, and wireless ADB environments.

---

## Prerequisites
- Android device running Android 11+ (API 30+) for Wireless Debugging, or rooted device (Magisk/KernelSU/APatch).
- Developer Options enabled:
  - Wireless Debugging enabled and paired (if testing ADB launch mode).
  - USB Debugging enabled.
- Shizuku debug build installed (`shizuku-v13.7.0-Zuher5-debug.apk`).

---

## Test Scenarios

### 1. Server Process Death Recovery (Root / Wireless ADB)
**Objective:** Verify that killing the server triggers watchdog recovery according to backoff and cooldown rules.
- **Steps:**
  1. Start Shizuku (via Root or Wireless ADB). Confirm Home screen displays "Shizuku is running".
  2. In terminal, kill the daemon:
     ```sh
     adb shell pkill -9 -f shizuku_server
     ```
  3. Observe logcat:
     ```sh
     adb logcat -s WatchdogManager WatchdogService ShizukuStateMachine
     ```
- **Expected Outcome:**
  - `ShizukuStateMachine` transitions `RUNNING -> CRASHED`.
  - `WatchdogService` detects binder death and invokes `handleUnhealthy()`.
  - Admission logic admits restart with 15s cooldown.
  - Service or worker restarts Shizuku and performs health check.
  - Home screen returns to "Shizuku is running".

---

### 2. Zombie / Hung Binder Detection
**Objective:** Verify that an unresponsive Binder transaction times out without blocking the watchdog loop or main thread.
- **Steps:**
  1. Trigger an unresponsive binder condition (e.g. pause server process with `SIGSTOP`):
     ```sh
     adb shell kill -STOP $(pidof shizuku_server)
     ```
  2. Observe watchdog health check logcat.
- **Expected Outcome:**
  - `WatchdogManager.checkHealth()` times out at 3,000ms.
  - `HealthResult` returns `status = ZOMBIE`.
  - Watchdog requests graceful stop, terminates hung server, and schedules clean recovery.
  - Resume server or restart to recover:
     ```sh
     adb shell kill -CONT $(pidof shizuku_server)
     ```

---

### 3. User Stop Suppression
**Objective:** Verify that explicitly stopping Shizuku prevents the watchdog from automatically restarting it.
- **Steps:**
  1. Start Shizuku.
  2. Tap "Stop" in the Shizuku app UI or trigger `ManualStopReceiver`.
  3. Wait past the health check interval (30s) and observe logcat.
- **Expected Outcome:**
  - `KEY_USER_STOP_REQUESTED` is set to `true`.
  - Watchdog logs `recovery skipped: user stop requested`.
  - Shizuku remains stopped until the user explicitly taps "Start".
  - Manual start clears `KEY_USER_STOP_REQUESTED` and restores watchdog recovery.

---

### 4. ADB Worker Deduplication and Lock Screen Behavior
**Objective:** Verify that multiple enqueue events do not spawn duplicate workers or cancel workers waiting for lock screen unlock.
- **Steps:**
  1. Configure launch mode to ADB.
  2. Lock device screen.
  3. Toggle network or trigger watchdog recovery.
- **Expected Outcome:**
  - `AdbStartWorker` uses `ExistingWorkPolicy.KEEP` and posts notification awaiting unlock.
  - `unlockReceiver` registers for `ACTION_USER_PRESENT`.
  - Subsequent network or watchdog events do not cancel or replace the pending worker.
  - Unlocking device triggers `ACTION_USER_PRESENT`, enables wireless debugging, completes discovery, and unregisters receiver cleanly without leaks.

---

### 5. Exponential Backoff and Attempt Exhaustion
**Objective:** Verify that repeated recovery failures back off exponentially and halt after maximum attempts.
- **Steps:**
  1. Disable wireless debugging in Developer Options so ADB connections fail.
  2. Kill Shizuku server:
     ```sh
     adb shell pkill -9 -f shizuku_server
     ```
  3. Monitor watchdog retry delays.
- **Expected Outcome:**
  - Attempt 1: 15s delay.
  - Attempt 2: 30s delay.
  - Attempt 3: 60s delay.
  - Attempt 4: 120s delay.
  - Attempt 5: halts with `AdmissionResult.Exhausted`.
  - No continuous retry loops; prevents battery drain.

---

### 6. Wi-Fi Reassert and Network Debouncing
**Objective:** Verify Wi-Fi network transitions are debounced and wireless debugging is re-armed after network settles.
- **Steps:**
  1. Toggle Wi-Fi off and on rapidly.
- **Expected Outcome:**
  - `AdbNetworkObserver` debounces triggers within 5,000ms window.
  - `reArmWifiAfterSettle` delays 3,000ms before asserting wireless debugging.
  - If Shizuku is already healthy, no redundant worker is started.
