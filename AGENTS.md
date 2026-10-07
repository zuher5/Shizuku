# AGENTS.md

## Project Identity

You are working on a personal reliability-focused fork of `thedjchi/Shizuku`.

The project is intentionally **not a Shevery clone**.

The correct mental model is:

```text
thedjchi Shizuku
       +
selected Shevery reliability techniques
       =
this fork
```

The application should still look and behave like thedjchi Shizuku.

---

# 1. Golden Rules

## Rule 1 — Preserve thedjchi

Treat the current repository as the primary source of truth.

Do not casually replace:

- UI
- navigation
- settings layout
- themes
- launch workflows
- API behavior
- package identity
- existing useful features

If a change is not required for reliability, do not make it.

---

## Rule 2 — Shevery is a reference, not the base

Use Shevery only to study reliability implementations.

Preferred imports/concepts:

- atomic state transitions
- reactive state observation
- `awaitRunning`
- `awaitStopped`
- expected-death protection
- user-stop protection
- Watchdog manager/service separation
- zombie Binder detection
- recovery verification
- restart serialization
- cooldown/backoff
- lockscreen-aware ADB recovery

Do not copy unrelated Shevery features.

---

## Rule 3 — Do not rewrite working code unnecessarily

Before changing a file:

1. inspect the current implementation;
2. understand its callers;
3. understand its lifecycle;
4. identify the smallest safe change;
5. implement;
6. compile/test.

Prefer incremental patches.

---

# 2. Source-of-Truth Hierarchy

When behavior conflicts:

```text
Current thedjchi repository
        ↓
Android platform requirements
        ↓
Shizuku upstream compatibility
        ↓
Selected Shevery reliability concepts
```

Never allow a Shevery feature to override existing thedjchi behavior simply because Shevery implements it differently.

---

# 3. Required Architecture

Target:

```text
ShizukuStateMachine
        │
        ▼
WatchdogManager
        │
        ▼
WatchdogService
        │
        ├── Binder crash detection
        ├── health checks
        ├── zombie detection
        └── recovery
```

### `ShizukuStateMachine`

Owns lifecycle state.

### `WatchdogManager`

Owns Watchdog decisions and recovery orchestration.

### `WatchdogService`

Owns the Android foreground-service lifecycle and periodic health monitoring.

Do not put all recovery logic directly inside `WatchdogService`.

---

# 4. State Machine Rules

States:

```kotlin
STARTING
RUNNING
STOPPING
STOPPED
CRASHED
```

Use atomic state transitions.

Avoid implementations where the transition function is evaluated more than once.

Preferred conceptual approach:

```kotlin
do {
    oldState = state.get()
    newState = transform(oldState)
    if (oldState == newState) return
} while (!state.compareAndSet(oldState, newState))
```

Notify listeners only after a successful state transition.

Listeners must not be notified repeatedly for the same state.

---

# 5. Binder Lifecycle

Register Binder listeners exactly once.

Required events:

```text
Binder received
Binder dead
```

On Binder received:

```text
STARTING → RUNNING
```

On unexpected Binder death:

```text
RUNNING → CRASHED
```

On intentional stopping:

```text
STOPPING → STOPPED
```

Do not classify every Binder death as a crash.

---

# 6. Expected Death

When intentionally stopping/restarting:

```kotlin
expectingDeath = true
```

Use a bounded expiration.

Recommended:

```text
30 seconds
```

When Binder death occurs:

```text
expected death active?
    YES → suppress crash recovery
    NO  → continue crash handling
```

Always clear stale expected-death state.

---

# 7. User Stop

User stop must be persistent.

When user explicitly stops:

```text
userStopRequested = true
```

Watchdog must not restart.

When the server is intentionally started:

```text
userStopRequested = false
```

Do not use a transient-only in-memory flag.

---

# 8. Recovery Guard

All recovery must pass through a single guarded path.

Use an atomic guard:

```kotlin
AtomicBoolean
```

Never allow:

```text
crash callback
+
health poll
+
boot receiver
+
manual restart
```

to start multiple recovery operations concurrently.

---

# 9. Cooldown

Use a monotonic clock such as:

```kotlin
SystemClock.elapsedRealtime()
```

Do not use wall-clock time for restart cooldown.

Initial recommended cooldown:

```text
15 seconds
```

---

# 10. Health Check

Do not rely only on:

```kotlin
Shizuku.pingBinder()
```

A healthy check should verify:

```text
Binder exists
    +
Binder ping works
    +
real lightweight IPC transaction works
    +
response is valid
```

The purpose is to detect:

> Binder exists but the underlying Shizuku server is no longer responsive.

This is called a zombie Binder state.

---

# 11. Health Polling

Use a moderate interval.

Initial target:

```text
30 seconds
```

If implementation/testing shows that 60 seconds is preferable for battery usage, use 60 seconds.

Never use tight polling.

Bad:

```kotlin
while (isActive) {
    checkHealth()
}
```

Good:

```kotlin
while (isActive) {
    delay(HEALTH_INTERVAL_MS)
    checkHealth()
}
```

---

# 12. False Positive Protection

Do not restart after a single transient health failure.

Initial policy:

```text
failure #1 → record
failure #2 → recovery
```

Reset consecutive failures after a successful health check.

---

# 13. Recovery Algorithm

Use this conceptual algorithm:

```text
health failure
      │
      ▼
check watchdog enabled
      │
      ▼
check user stop
      │
      ▼
check expected death
      │
      ▼
check recovery lock
      │
      ▼
check cooldown
      │
      ▼
start recovery
      │
      ▼
select launch method
      │
      ├── ROOT
      ├── ADB
      └── TCP
      │
      ▼
await Binder
      │
      ▼
perform health check
      │
   ┌──┴──┐
   ▼     ▼
 PASS   FAIL
   │     │
   ▼     ▼
RUNNING backoff/retry
```

---

# 14. Root Recovery

Use the existing thedjchi root starter.

Do not invent a new root server-launch implementation unless the existing one is demonstrably broken.

Keep the existing starter command/path.

---

# 15. ADB Recovery

ADB recovery requires special care.

Android may prevent or tear down plain TCP ADB while the device is locked.

Therefore:

```text
ADB recovery requested
        │
        ▼
device locked?
    │          │
   YES         NO
    │           │
    ▼           ▼
wait for      recover
USER_PRESENT
    │
    ▼
recover
```

Do not endlessly retry while the device is locked.

Prefer the existing worker infrastructure when available.

---

# 16. TCP Recovery

Do not replace thedjchi TCP implementation without evidence.

Preserve its behavior.

Only add reliability around it:

- state tracking;
- cooldown;
- retry;
- verification;
- lockscreen handling where appropriate.

---

# 17. Recovery Verification

After starting the server:

```text
wait for Binder
```

Then:

```text
health check
```

Only then:

```text
recovery succeeded
```

Never report success merely because a start command returned successfully.

---

# 18. Backoff

Use bounded exponential backoff for repeated failures.

Suggested:

```text
15s
30s
60s
120s
```

Maximum:

```text
5 attempts
```

The exact values may be tuned after testing.

Never implement an infinite aggressive recovery loop.

---

# 19. Watchdog Service

`WatchdogService` should primarily handle:

- Android service lifecycle;
- foreground notification;
- lifecycle-safe monitoring;
- Binder event observation;
- periodic health scheduling.

Complex recovery decisions belong in `WatchdogManager`.

---

# 20. Foreground Service

Follow the existing Android version handling.

For Android versions requiring a foreground service type:

- use the correct type already supported by the project;
- do not remove required manifest declarations;
- do not introduce an invalid FGS type.

Handle:

```text
ForegroundServiceStartNotAllowedException
```

gracefully where applicable.

A Watchdog failure must not crash the manager.

---

# 21. Notifications

Keep notifications minimal.

Watchdog notification:

```text
ongoing
low priority
silent
```

Crash notification:

```text
only on unexpected failure
```

Recovery notification:

```text
optional
```

Do not spam the user.

---

# 22. Settings

Do not redesign settings.

If Watchdog already exists in thedjchi:

- preserve its existing placement;
- preserve existing visual style;
- improve its underlying implementation.

If additional options are required, add them minimally.

---

# 23. Dependencies

Do not add a dependency when the Android/Kotlin standard library or existing project dependency is sufficient.

Before adding a dependency:

1. verify it is actually required;
2. check Android compatibility;
3. check APK size impact;
4. check maintenance status;
5. explain why it is needed.

---

# 24. Threading

Use:

- Kotlin coroutines;
- appropriate dispatchers;
- atomic primitives;
- lifecycle-aware jobs.

Do not block the main thread with:

- Binder waits;
- process waits;
- shell commands;
- network operations;
- long sleeps.

---

# 25. Exception Handling

At Android lifecycle boundaries, handle platform exceptions defensively.

Do not blindly do:

```kotlin
catch (Throwable) {}
```

unless there is a clear reason.

When catching:

```text
log enough information to diagnose the failure
```

but never log secrets or sensitive data.

---

# 26. Logging Convention

Use the project's existing logging utilities where possible.

Useful messages:

```text
Watchdog initialized
Watchdog enabled
Watchdog disabled
Binder received
Binder died
Expected death
User stop requested
Health check passed
Health check failed
Zombie binder detected
Recovery started
Recovery skipped
Recovery succeeded
Recovery failed
Cooldown active
Waiting for USER_PRESENT
Maximum recovery attempts reached
```

Logs should explain **why** an action happened.

---

# 27. Git Discipline

Make changes in logical commits when possible.

Preferred sequence:

```text
1. state machine reliability
2. watchdog manager
3. watchdog service
4. health/zombie detection
5. ADB recovery
6. settings/notifications
7. tests
```

Do not mix unrelated refactors into these commits.

Avoid mass formatting changes.

Avoid changing thousands of lines for a small feature.

---

# 28. Testing Before Completion

Unit tests / static checks:

```text
./gradlew test
```

(Allowed locally only if executing pure JVM tests without assembling or packaging an APK artifact. Do NOT run local Android application build/assemble tasks — all APK building is strictly reserved for GitHub Actions).

Also perform static inspection for:

- race conditions;
- duplicate listeners;
- leaked coroutines;
- service lifecycle issues;
- infinite retry loops;
- incorrect foreground service usage.

If an Android device/emulator is available, test:

### Normal

```text
start
stop
restart
```

### Crash

```text
kill Shizuku server
verify recovery
```

### Zombie

```text
simulate/induce failed Binder transaction
verify detection
```

### ADB

```text
screen unlocked
screen locked
unlock
Wi-Fi reconnect
```

### Watchdog

```text
enable
disable
process restart
boot
```

---

# 29. Regression Protection

Before declaring success, verify:

```text
Root start       ✓
ADB start        ✓
TCP mode         ✓
Stealth mode     ✓
Boot behavior    ✓
Authorization    ✓
Existing UI      ✓
Existing intents ✓
Shizuku clients  ✓
```

---

# 30. Important Constraint

Do not claim:

> "Shizuku can never die."

Android can kill processes, restrict services, disable network paths, revoke/alter ADB state, or otherwise prevent recovery.

The correct product promise is:

> "Detect failure and recover automatically whenever the system and selected launch method allow it."

---

# 31. When Unsure

If an implementation decision is unclear:

1. inspect the current thedjchi implementation;
2. inspect how callers use it;
3. inspect the relevant Android lifecycle behavior;
4. inspect the corresponding Shevery implementation;
5. choose the smallest change that preserves compatibility;
6. test before proceeding.

Do not make architectural assumptions from filenames alone.

---

# 32. Final Quality Gate

Before reporting completion, confirm:

```text
[ ] GitHub Actions build passes (authoritative)
[ ] Tests pass
[ ] Existing UI preserved
[ ] Existing launch methods preserved
[ ] State machine is atomic
[ ] Binder death is detected
[ ] Expected death is handled
[ ] User stop is respected
[ ] Zombie Binder is detected
[ ] Recovery is serialized
[ ] Recovery has cooldown/backoff
[ ] Recovery is verified
[ ] ADB recovery handles lockscreen
[ ] No busy loops
[ ] No infinite restart loop
[ ] No unnecessary dependencies
[ ] No unrelated refactor
```

If any item is not satisfied, report it explicitly instead of claiming the implementation is complete.


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
