# PRD — Shizuku Reliability Fork

## 1. Project Overview

This project is a personal fork of `thedjchi/Shizuku`.

The goal is **not** to redesign Shizuku and **not** to turn it into Shevery.

The goal is:

> Keep the thedjchi Shizuku user experience, UI, existing behavior, compatibility, and useful features, while selectively integrating the strongest reliability/recovery concepts from Shevery.

The resulting application should feel like **thedjchi Shizuku**, but its Shizuku server lifecycle should be significantly more resilient.

### Base repository

- Base: `thedjchi/Shizuku`
- Reference reliability implementation: `HmnDev-Tech/shevery`

Use the repositories as references. Do not blindly copy entire subsystems.

---

# 2. Product Goals

## Primary goals

1. Preserve the existing thedjchi UI.
2. Preserve existing Shizuku API compatibility.
3. Preserve existing launch methods.
4. Make Watchdog significantly more reliable.
5. Detect unexpected server crashes.
6. Detect zombie Binder/server states.
7. Automatically recover the server when recovery is appropriate.
8. Verify that recovery actually succeeded.
9. Avoid restart loops and battery-heavy polling.
10. Correctly distinguish intentional user stops from crashes.
11. Handle ADB recovery safely while the device is locked.
12. Keep the implementation maintainable and modular.

## Secondary goals

- Improve lifecycle/state handling.
- Make server state observable through a centralized state machine.
- Provide useful recovery/death notifications.
- Make recovery behavior deterministic and testable.
- Keep the fork easy to update from upstream/the existing base.

---

# 3. Non-Goals

Do NOT turn this project into a full Shevery clone.

Do NOT implement these unless explicitly requested later:

- Shevery UI redesign
- Compose migration
- Material 3 redesign
- AI/Gemini features
- Shevery branding
- Shevery package identity
- unrelated Shevery automation features
- unrelated experimental features
- unnecessary dependency additions
- large architectural rewrites
- changing Shizuku's public API without a strong reason

The core principle is:

> Reliability improvements only. Keep the thedjchi identity.

---

# 4. Design Principles

## 4.1 Base-first

The thedjchi repository is the source of truth for:

- UI
- existing workflows
- existing launch methods
- existing intents
- existing compatibility behavior
- existing package/API behavior

Shevery is only a reference for selected reliability improvements.

## 4.2 Minimal invasive changes

Prefer small, isolated changes over rewriting existing systems.

## 4.3 Recovery must be verified

Never assume:

```text
restart() == success
```

Recovery is successful only when the Shizuku Binder becomes available and a real Binder transaction succeeds.

## 4.4 Never fight the user

If the user explicitly stops Shizuku, Watchdog must not immediately restart it.

## 4.5 Avoid restart storms

Multiple crash signals may happen at the same time.

Recovery must be serialized and rate-limited.

## 4.6 Android lifecycle constraints matter

Android may restrict background/foreground service behavior depending on Android version, device vendor, lockscreen state, battery optimization, and permissions.

Do not promise that Shizuku can literally "never die".

The objective is:

> detect failures quickly, recover when technically possible, and fail gracefully when Android prevents recovery.

---

# 5. Target Architecture

Expected high-level architecture:

```text
                     Shizuku Binder
                          │
             ┌────────────┴────────────┐
             │                         │
      Binder Received             Binder Dead
             │                         │
             ▼                         ▼
       StateMachine              StateMachine
             │                         │
             ▼                         ▼
          RUNNING                   CRASHED
             │                         │
             │                         ▼
             │                  WatchdogManager
             │                         │
             │                  ┌──────┴──────┐
             │                  │             │
             │               recovery      health
             │                  │            check
             │                  │             │
             │                  └──────┬──────┘
             │                         │
             │                         ▼
             │                    Recovery
             │                         │
             │             ┌───────────┼───────────┐
             │             ▼           ▼           ▼
             │            ROOT         ADB       TCP/Dhizuku
             │                         │
             │                         ▼
             │                  Lockscreen-aware
             │                     recovery
             │                         │
             └─────────────────────────┘
                          │
                          ▼
                   Verify Binder health
                          │
                  ┌───────┴───────┐
                  ▼               ▼
               HEALTHY          FAILED
                  │               │
                  ▼               ▼
                RUNNING          BACKOFF
```

---

# 6. State Machine

Upgrade the existing `ShizukuStateMachine`.

States:

```kotlin
STARTING
RUNNING
STOPPING
STOPPED
CRASHED
```

Required behavior:

### STARTING

Server launch has been requested.

### RUNNING

Binder is alive and usable.

### STOPPING

Explicit stop/restart is in progress.

### STOPPED

Server is intentionally stopped or not running.

### CRASHED

Binder died unexpectedly while the server was previously running.

---

# 7. State Machine Requirements

The implementation must:

- use atomic state transitions;
- avoid calling transition functions multiple times unnecessarily;
- notify listeners only when the state actually changes;
- register Binder listeners once;
- support reactive observation;
- provide current state immediately to new listeners.

Required APIs:

```kotlin
fun get(): State
fun set(newState: State)
fun update(): State
fun isRunning(): Boolean
fun isDead(): Boolean
fun addListener(listener: (State) -> Unit)
fun removeListener(listener: (State) -> Unit)
fun asFlow(): Flow<State>
```

Add:

```kotlin
suspend fun awaitRunning(timeoutMs: Long = 10_000L): Boolean
suspend fun awaitStopped(timeoutMs: Long = 5_000L): Boolean
```

`awaitRunning()` must return immediately if the server is already running.

`awaitStopped()` must return immediately if the server is already stopped.

Timeouts must always be bounded.

---

# 8. Watchdog

The Watchdog is the main feature of this fork.

## 8.1 Responsibilities

Watchdog must:

1. monitor Binder lifecycle;
2. detect unexpected crashes;
3. detect zombie Binder states;
4. initiate recovery;
5. serialize recovery attempts;
6. prevent restart loops;
7. distinguish user stop from crash;
8. verify recovery;
9. provide useful logs;
10. optionally notify the user.

---

# 9. Binder Crash Detection

Use the Shizuku Binder death listener.

When Binder dies:

```text
RUNNING → CRASHED
```

Then Watchdog evaluates:

```text
Is watchdog enabled?
Is this an expected death?
Did user intentionally stop Shizuku?
Was a restart already in progress?
Is recovery allowed?
```

Only then should recovery begin.

---

# 10. Expected Death Protection

Add an expected-death mechanism.

When an intentional stop/restart is requested:

```kotlin
expectingDeath = true
```

The expected-death state must have a bounded expiration window.

Recommended default:

```text
30 seconds
```

If Binder dies during the window:

```text
expected death
```

Do not trigger crash recovery.

If the deadline expires:

```text
clear stale expected-death state
```

This prevents a stale flag from disabling Watchdog forever.

---

# 11. User Stop Protection

Persist a flag such as:

```text
watchdog_user_stop_requested
```

When the user explicitly stops Shizuku:

```text
userStopRequested = true
```

Watchdog must not automatically restart it.

When the user starts Shizuku again:

```text
userStopRequested = false
```

The preference must survive process restarts.

---

# 12. Recovery Serialization

Only one restart may be active at a time.

Use an atomic guard such as:

```kotlin
AtomicBoolean
```

Example:

```text
restart request A → accepted
restart request B → ignored
restart request C → ignored
```

This prevents:

- duplicate starts;
- race conditions;
- multiple workers;
- restart storms.

---

# 13. Restart Cooldown

Use a minimum restart interval.

Recommended initial value:

```text
15 seconds
```

The value should be centralized and easy to tune.

If a recovery attempt occurred too recently:

```text
skip recovery
```

Do not silently loop.

Log the reason.

---

# 14. Recovery Methods

Recovery must respect the original launch method.

## ROOT

Use the existing thedjchi root starter mechanism.

Do not introduce a second root-start architecture unless necessary.

## ADB

Use the existing ADB infrastructure where possible.

Recovery must be lockscreen-aware.

Do not repeatedly attempt plain TCP/ADB recovery behind the lockscreen if Android is known to reject it.

When appropriate:

```text
device locked
    ↓
wait for USER_PRESENT
    ↓
run ADB recovery
```

The recovery implementation should preferably use a worker for complex ADB restart logic.

## TCP / Wireless ADB

Preserve the existing thedjchi implementation.

Improve reliability without replacing its entire TCP architecture.

## Dhizuku

Do not make Dhizuku recovery a first-phase requirement.

Keep architecture extensible so Dhizuku recovery can be added later.

---

# 15. Zombie Binder Detection

A process can be alive while the Binder is effectively unusable.

Therefore:

```kotlin
Shizuku.pingBinder()
```

alone is insufficient.

Health check should:

1. obtain the Binder;
2. verify Binder ping;
3. create/access `IShizukuService`;
4. perform a lightweight real Binder transaction;
5. validate the response.

Example conceptual flow:

```text
getBinder()
    ↓
pingBinder()
    ↓
IShizukuService
    ↓
lightweight IPC transaction
    ↓
validate result
```

If ping succeeds but the transaction fails:

```text
ZOMBIE
```

---

# 16. Health Polling

Do not continuously poll at a high frequency.

Recommended initial interval:

```text
30–60 seconds
```

Recommended implementation target:

```text
30 seconds
```

This value must be configurable in code.

To avoid false positives:

```text
FAILURE #1 → record
FAILURE #2 → recovery
```

Recommended:

```text
FAILURES_TO_RESTART = 2
```

The implementation must reset the failure counter after a healthy check.

---

# 17. Recovery Verification

After recovery:

```text
restart
    ↓
awaitRunning()
    ↓
health check
    ↓
success?
```

Do not mark recovery successful merely because a process was started.

Recommended verification timeout:

```text
10–15 seconds
```

If recovery fails:

```text
log failure
apply backoff
optionally notify user
```

---

# 18. Backoff

If recovery repeatedly fails, use bounded exponential backoff.

Suggested sequence:

```text
15s
30s
60s
120s
```

Maximum attempts should be bounded.

Recommended initial maximum:

```text
5 attempts
```

After the maximum:

```text
stop automatic recovery
notify/log failure
```

The system should not consume battery indefinitely trying to restart a fundamentally unavailable launch method.

---

# 19. Notifications

Notifications should remain simple.

## Watchdog active

Persistent low-priority foreground notification.

Should communicate:

```text
Shizuku Watchdog is active
```

and provide a way to disable Watchdog.

## Unexpected crash

Notify only for an actual unexpected failure.

Notification may contain:

- Shizuku stopped unexpectedly
- Open Shizuku
- optionally retry/restart
- optionally open notification settings

## Recovery succeeded

Optional notification.

Must be configurable if implementation includes it.

Avoid notification spam.

---

# 20. Boot Handling

On boot:

1. initialize the state machine;
2. initialize Watchdog;
3. reconcile Watchdog state;
4. preserve existing thedjchi boot behavior;
5. do not automatically start Shizuku unless the existing launch configuration requires it.

The Watchdog itself should be started only when its setting is enabled.

---

# 21. Watchdog Reconciliation

Implement a central operation conceptually equivalent to:

```kotlin
WatchdogManager.reconcileService(context)
```

Behavior:

```text
watchdog enabled + user did not request stop
        ↓
start watchdog service

watchdog disabled
        ↓
stop watchdog service
```

Calling reconcile multiple times must be safe.

---

# 22. Settings

Keep the existing thedjchi settings UI style.

Add or preserve only the minimum required Watchdog settings.

Primary toggle:

```text
Watchdog
```

Optional advanced settings:

```text
Notify on crash
Notify on recovery
```

Do not redesign the settings page.

---

# 23. Performance Requirements

The Watchdog must be lightweight.

Avoid:

- tight loops;
- busy waiting;
- frequent Binder calls;
- unnecessary wakeups;
- unnecessary foreground services;
- unnecessary allocations.

Health monitoring should use:

```text
Coroutine delay
```

or another lifecycle-safe mechanism.

Do not use:

```kotlin
while (true) {
    // busy work
}
```

---

# 24. Compatibility

Preserve:

- existing package/API behavior;
- Shizuku API compatibility;
- existing permissions;
- existing launch modes;
- existing intents;
- existing application behavior;
- existing UI;
- existing translation resources where possible.

Do not break apps that already depend on the fork.

---

# 25. Logging

Use structured, meaningful logs.

Examples:

```text
Watchdog: initialized
Watchdog: binder died
Watchdog: death was expected
Watchdog: user stop requested
Watchdog: health check passed
Watchdog: zombie binder detected
Watchdog: recovery started
Watchdog: waiting for USER_PRESENT
Watchdog: recovery succeeded
Watchdog: recovery failed
Watchdog: cooldown active
Watchdog: maximum recovery attempts reached
```

Do not log sensitive information.

---

# 26. Error Handling

Watchdog code must be defensive.

Android lifecycle/service operations can throw exceptions.

Catch expected platform failures and log them.

Never allow a Watchdog exception to crash the manager application.

Prefer:

```kotlin
try {
    ...
} catch (e: Throwable) {
    logw(...)
}
```

only at appropriate Android boundary points.

Do not indiscriminately swallow exceptions inside core logic.

---

# 27. Testing Requirements

At minimum test:

## State machine

- initial state;
- binder received;
- binder death;
- explicit stop;
- restart;
- concurrent transitions;
- listener registration;
- listener removal;
- `awaitRunning`;
- `awaitStopped`.

## Watchdog

- disabled watchdog;
- enabled watchdog;
- user stop;
- unexpected crash;
- expected death;
- stale expected death;
- duplicate restart requests;
- cooldown;
- successful recovery;
- failed recovery;
- zombie Binder;
- recovery timeout.

## ADB

Test:

- unlocked device;
- locked device;
- USER_PRESENT recovery;
- unavailable ADB;
- Wi-Fi disconnected;
- Wi-Fi reconnect.

## Regression

Verify:

- existing UI;
- root start;
- ADB start;
- TCP mode;
- existing intents;
- permissions;
- application authorization;
- API compatibility.

---

# 28. Acceptance Criteria

The project is considered successful when:

### UI

- The application still visually behaves like thedjchi fork.
- No unnecessary Shevery UI is introduced.

### Watchdog

- Unexpected Binder death is detected.
- Intentional user stop does not trigger automatic restart.
- Zombie Binder is detectable.
- Recovery is serialized.
- Recovery is rate-limited.
- Recovery is verified.
- ADB recovery is lockscreen-aware.
- Recovery does not create an infinite restart loop.

### Stability

- Watchdog itself does not crash the manager.
- No obvious busy-loop behavior.
- No unnecessary high-frequency polling.

### Compatibility

- Existing Shizuku API clients continue working.
- Existing launch methods continue working.
- Existing thedjchi features remain functional.

---

# 29. Implementation Order

Implement in this order:

## Phase 1

Upgrade:

```text
ShizukuStateMachine
```

Add atomic transitions and await APIs.

## Phase 2

Refactor/create:

```text
WatchdogManager
```

Move decision logic out of the service.

## Phase 3

Upgrade:

```text
WatchdogService
```

Add:

- Binder death handling
- state listener
- health polling
- zombie detection

## Phase 4

Add:

- user-stop protection
- expected-death timeout
- restart mutex
- cooldown
- recovery verification

## Phase 5

Improve:

```text
ADB recovery
```

with lockscreen awareness.

## Phase 6

Add:

- boot reconciliation
- settings integration
- notifications

## Phase 7

Run regression tests and build release APK.

---

# 30. Definition of Done

The implementation is done only when:

```text
Build succeeds
        AND
Existing thedjchi features still work
        AND
Watchdog detects real crashes
        AND
Watchdog detects zombie Binder
        AND
User stop is respected
        AND
Recovery is verified
        AND
ADB recovery handles lockscreen
        AND
No uncontrolled restart loop exists
```

Do not mark the task complete merely because the project compiles.


---

# STRICT BUILD POLICY

## Core Rule

The local development environment MUST NOT build the Android application.

**GitHub Actions is the only authoritative environment allowed to build the Android application.**

Local development is for:

- editing source code;
- inspecting code;
- preparing commits;
- Git operations;
- lightweight static checks that do not produce an Android application artifact.

Local development must NOT produce an APK or any other application artifact. Only APK builds are targeted by this project (AAB is strictly not built or supported).

Local unit testing: Pure JVM unit tests or static checks (e.g. `./gradlew test`) are permitted locally ONLY if they do not compile or package an Android APK artifact. Local execution of any task that produces, assembles, or packages an application artifact (APK/AAB) is strictly prohibited.

## Prohibited Local Build Tasks

Do NOT run local Gradle tasks that build the Android application, including:

```text
./gradlew build
./gradlew assemble
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew bundle
./gradlew bundleDebug
./gradlew bundleRelease
```

Also prohibit equivalent application-producing Gradle tasks, even if they use different task names.

Do not use local Android Studio build/run actions that compile/package the application.

## Required Workflow

```text
Edit source code locally
        ↓
Inspect / review changes
        ↓
Commit
        ↓
Push to GitHub
        ↓
GitHub Actions
        ↓
Actual Android build
        ↓
Tests / checks
        ↓
APK generation (APK ONLY; NO AAB)
        ↓
GitHub Actions artifacts
```

## GitHub Actions Requirements

GitHub Actions MUST:

1. checkout the repository;
2. configure the required JDK/Android environment;
3. restore Gradle caches where useful;
4. execute the actual Android build;
5. run relevant tests/checks;
6. generate the required APK artifact only (do NOT generate AAB);
7. upload the resulting artifacts to the workflow run.

Gradle caching is allowed in GitHub Actions only as an optimization.

```text
cache ≠ build
```

A cache hit must never replace the actual Android build step.

## Verification Rule

A local environment cannot be used as evidence that the Android application builds successfully.

The authoritative result is:

```text
GitHub Actions = PASS
```

If:

```text
Local code looks correct
GitHub Actions = FAIL
```

then the implementation is **not complete**.

The agent must investigate and fix the CI failure.

## Agent Rule

When an implementation needs build verification:

```text
DO NOT build the app locally.
DO push the changes to GitHub.
DO wait for GitHub Actions.
DO inspect the GitHub Actions result.
DO fix failures.
DO report success only after GitHub Actions passes.
```

If GitHub Actions cannot be executed or its result cannot be inspected, explicitly report:

```text
CI VERIFICATION NOT COMPLETED
```

Never claim that the application build passed based on a local build.

## Final Build Authority

There is exactly one source of truth for application builds:

> **GitHub Actions.**
