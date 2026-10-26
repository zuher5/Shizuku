# Manual Test Notes (device dependent)

The watchdog, ADB starter and native supervisor changes in this fork cannot be
verified on a build server. These scenarios must be run on a real device.

## Prerequisites
- Android 11+ (API 30+) for wireless debugging, or a rooted device.
- Developer options enabled, wireless debugging paired (for the ADB path).
- The debug build installed (for example, `shizuku-v13.7.4-Zuher5-debug.apk`).

Logcat filter:
```sh
adb logcat -s WatchdogManager WatchdogService ShizukuStateMachine AdbStartWorker Shizuku
```

---

## 1. Root start and the native supervisor
**Goal:** the server starts, and a killed server is respawned by the supervisor.
- Start Shizuku with root.
- Kill the server:
  ```sh
  adb shell pkill -9 -f shizuku_server
  ```
- **Expected:** within ~5 seconds the server is back (`Shizuku: shizuku_server pid is <pid>` in logcat).
- **Expected:** the launcher (StarterActivity / adb session) is not blocked; the starter prints the server pid and exits.

## 2. Supervisor restart limit
**Goal:** a crash loop does not respawn forever.
- Make the server exit immediately (for example revoke a permission it needs) and kill it repeatedly.
- **Expected:** at most 5 consecutive respawns, then the supervisor gives up (`shizuku_server crashed 5 times consecutively, giving up`).

## 3. Watchdog recovery after a crash
**Goal:** the client watchdog restarts the server when the supervisor does not.
- Enable the watchdog in the settings, start Shizuku, then kill the server and immediately block the respawn (for example stop the app that hosts the shell).
- **Expected:** `Watchdog: recovery started (mode=..., attempt=1/5, delay=15000ms)`, the server comes back, and the watchdog reports `recovery succeeded`.

## 4. User stop is not undone
**Goal:** a manual stop stays stopped.
- Start Shizuku, stop it from the app (the stop action in the home screen).
- **Expected:** the server stays down; the watchdog logs `user stop requested` and does not restart it.
- Start Shizuku again from the app: the user-stop flag is cleared and the watchdog works again.

## 5. Watchdog toggle only disables the watchdog
**Goal:** turning the watchdog off does not stop the server.
- With the watchdog enabled, tap the "turn off" action on the watchdog notification.
- **Expected:** the watchdog service stops, the server keeps running, and Shizuku is not stopped.

## 6. Zombie (hung) server detection
**Goal:** an unresponsive server is detected and restarted.
- Freeze the server process:
  ```sh
  adb shell kill -STOP $(pidof shizuku_server)
  ```
- **Expected:** `Watchdog: health check failed [...]: binder transaction timed out after 3000ms`, the server is stopped, and a fresh one is started.
- Resume the frozen process afterwards; it must not fight the new server.

## 7. ADB start with a locked screen
**Goal:** the worker waits for the unlock instead of looping.
- Set the launch mode to ADB, lock the screen, and trigger a start (boot or the watchdog).
- **Expected:** the worker waits up to 30 seconds, then retries with backoff; unlocking the device resumes the start.

## 8. ADB start, wireless debugging
**Goal:** the normal wireless path still works.
- Start Shizuku with wireless debugging.
- **Expected:** mDNS discovery finds the port, the server starts, and the "starting" notification disappears.

## 9. TCP mode
**Goal:** the TCP path is not broken by the reworked starter.
- Enable TCP mode with a configured port, then start Shizuku.
- **Expected:** adbd is rebound to the TCP port, the server starts, and the wireless debugging flag is only cleared after a successful start.

## 10. Wi-Fi only later (boot on cellular)
**Goal:** the network observer restarts Shizuku when Wi-Fi returns.
- Boot on cellular with start-on-boot enabled, then connect to Wi-Fi.
- **Expected:** the start worker is enqueued and the server starts.

## 11. "Attempt now" in the notification
**Goal:** the manual retry replaces a pending worker.
- Trigger a start that cannot succeed (for example wireless debugging not authorized), then tap "attempt now".
- **Expected:** a new attempt starts immediately instead of the old one being kept.

## 12. Authenticated stop/start while ADB work is pending
**Goal:** an explicit stop cannot be undone by an in-flight or retrying ADB start, and a later explicit start clears the stop request.
- Trigger an ADB start that is waiting for Wi-Fi, authorization, or mDNS discovery.
- Send the existing authenticated `${applicationId}.STOP` broadcast (with the configured `auth` extra).
- **Expected:** the pending/retrying `adb_start_worker` is cancelled, its progress notification is removed, and no server starts afterward.
- Send the authenticated `${applicationId}.START` broadcast or tap "attempt now" on the existing start notification.
- **Expected:** the persisted user-stop flag is cleared and normal ADB startup proceeds.
- Repeat stop with the server already stopped; **expected:** it remains stopped and no queued worker restarts it.
