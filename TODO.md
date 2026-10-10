# TODO — Shizuku Fork Reliability Improvements

**Repository:** `zuher5/Shizuku`  
**Base branch:** `master`

**Guiding rule:** Preserve the existing thedjchi UI/UX, package/API compatibility, and current launch/network features. Use Shevery as a reference for reliability patterns only; do not rewrite the project to match Shevery.

## Priority overview

- [ ] **P0 — Fix watchdog correctness and concurrency**
- [ ] **P0 — Bound and harden Binder health checks**
- [ ] **P0 — Fix recovery attempt accounting and backoff**
- [ ] **P1 — Make ADB unique-work scheduling race-safe**
- [ ] **P1 — Add automated tests for state/recovery decisions**
- [ ] **P1 — Add CI for pushes and pull requests**
- [ ] **P2 — Evaluate heartbeat monitoring**
- [ ] **P2 — Evaluate additional launch-mode recovery only if supported**

---

## Phase 0 — Baseline and safety

- [x] Confirm the working tree is clean and create a dedicated feature branch.
- [x] Read `PRD.md` and `AGENTS.md` before editing.
- [x] Record the current build result and relevant Gradle tasks.
- [x] Inspect launch modes, settings keys, Binder APIs, manifest service declarations, and existing ADB/TCP startup paths.
- [x] Check licenses and preserve required attribution for adapted code.
- [x] Keep changes focused; do not redesign UI or migrate to Compose/Material 3.

**Done when:** the starting commit, build command, affected files, and compatibility constraints are recorded.

## Phase 1 — Fix Watchdog correctness

### 1.1 Make initialization/listener registration thread-safe

- [x] Replace the non-atomic `initialized` check/set in `WatchdogManager.init()` with a thread-safe one-time initialization pattern.
- [x] Ensure Binder listeners are registered exactly once even when initialization is triggered from multiple entry points.
- [x] Verify `Application`, boot receiver, and service startup can all call initialization safely.
- [x] Avoid holding a lock while performing callbacks or long-running work.

**Tests**
- [ ] Concurrent initialization calls register listeners only once.
- [ ] Repeated startup does not duplicate crash notifications or recovery attempts.

### 1.2 Make recovery admission atomic

- [x] Review ordering of user-stop, watchdog-enabled, expected-death, launch-mode, retry-limit, cooldown, and restart-lock checks.
- [x] Make cooldown/retry admission and acquisition of the restart lock race-safe.
- [x] Ensure only one recovery attempt can start at a time.
- [x] Set the restart timestamp only when an attempt is actually admitted.
- [x] Always release the restart lock in `finally`, including exceptions and cancellation.
- [x] Use a lifecycle-owned coroutine scope or clearly owned scope instead of creating an untracked `CoroutineScope(Dispatchers.IO)` for every attempt.
- [x] Handle `CancellationException` separately; do not swallow coroutine cancellation as a generic failure.

**Tests**
- [ ] Two simultaneous crash/health events start at most one recovery.
- [ ] Cooldown prevents rapid duplicate attempts.
- [ ] An exception cannot leave the restart lock stuck.

### 1.3 Fix retry accounting and backoff

- [x] Count failed recovery paths consistently, including exceptions and timeouts.
- [x] Mark recovery successful only after Binder returns and the health check passes.
- [x] Reset retry count only after verified recovery.
- [x] Confirm backoff indexing and first-retry delay are correct.
- [x] After maximum attempts, stop automatic retries until an explicit reset/re-enable or a defined new recovery session.
- [x] Log attempt number, reason, delay, and exhausted state without log spam.

**Tests**
- [ ] Timeout increments attempt count.
- [ ] Exceptions increment attempt count.
- [ ] Healthy recovery resets the count.
- [ ] Maximum attempts stops further recovery.
- [ ] Backoff follows the documented sequence.

### 1.4 Preserve expected-death and user-stop semantics

- [x] Verify intentional stop/restart operations do not trigger crash recovery.
- [x] Verify user-requested stop persists across process recreation.
- [x] Verify expected-death state expires and cannot suppress recovery indefinitely.
- [x] Verify explicit server start clears the user-stop flag only where intended.
- [x] Verify disabling watchdog stops monitoring and prevents new recovery attempts.

**Tests**
- [ ] User stop does not auto-restart the server.
- [ ] Intentional restart does not create false crash recovery.
- [ ] Stale expected-death state eventually stops suppressing real failures.

## Phase 2 — Bound and harden Binder health checks

- [x] Review `WatchdogManager.checkHealth()` and every synchronous Binder call it makes.
- [x] Keep health checks off the main thread.
- [x] Use bounded execution that stops waiting when a Binder transaction hangs; do not assume `withTimeout` cancels a synchronous Binder call.
- [x] Avoid creating an unbounded number of stuck health-check threads.
- [x] Define health outcomes clearly: no/dead Binder, alive but unresponsive (zombie), and healthy.
- [x] Keep real remote transactions only if safe, cheap, and supported by the current Shizuku API.
- [x] For a zombie Binder, request graceful stop, wait for stopped/dead state with a bounded timeout, then recover.
- [x] Require a successful post-recovery health check before declaring recovery successful.
- [x] Ensure failed health checks cannot trigger overlapping recovery attempts.

**Tests**
- [ ] Missing Binder is classified as dead.
- [ ] Binder ping failure is classified correctly.
- [ ] Remote transaction failure is classified as unhealthy.
- [ ] A hung transaction does not freeze the main thread or create unlimited concurrent checks.
- [ ] Recovery is not marked successful until the health check passes.

## Phase 3 — Make ADB worker scheduling race-safe

- [x] Review `AdbStartWorker.enqueueIfIdle()` and current `ExistingWorkPolicy`.
- [x] Remove synchronous WorkManager status reads from sensitive execution paths.
- [x] Prefer unique-work semantics that prevent duplicate workers without cancelling valid in-progress recovery.
- [x] Recheck Binder/server state inside the worker immediately before startup work.
- [x] Ensure a second watchdog event cannot replace/cancel a worker already handling ADB authentication or waiting for unlock.
- [x] Preserve current Wi-Fi/TCP behavior and notifications.
- [x] Verify receiver and ContentObserver cleanup on success, failure, timeout, and cancellation.
- [x] While locked, avoid repeated wireless ADB retries when user authorization is required; resume appropriately after `USER_PRESENT`.
- [x] Keep retries bounded and compatible with WorkManager constraints.

**Tests**
- [ ] Multiple enqueue requests produce at most one effective worker.
- [ ] An already-running worker is not accidentally replaced.
- [ ] If Shizuku becomes healthy before worker execution, startup exits safely.
- [ ] Unlock/authentication flow cleans up receivers and observers.
- [ ] Existing ADB, Wi-Fi debugging, and TCP paths still work.

## Phase 4 — Add automated tests

- [x] Add unit tests for `ShizukuStateMachine` transitions and concurrent updates.
- [x] Add tests for Watchdog decisions using testable abstractions/fakes where practical.
- [x] Cover user-stop, expected-death, disabled watchdog, unknown launch mode, cooldown, backoff, maximum attempts, and recovery verification.
- [x] Keep tests deterministic; prefer fake clocks/injected dispatchers over real timing sleeps.
- [x] Add instrumentation/manual test notes for device-dependent Binder and wireless ADB cases.
- [x] Avoid broad refactors solely for testability; extract small interfaces only where needed.

**Done when:** core state/recovery decisions have automated regression coverage and device-only scenarios are documented.

## Phase 5 — Add CI for push and pull request

- [x] Update `.github/workflows/app.yml` or add a dedicated CI workflow for `push` and `pull_request`.
- [x] Keep release/tag creation separate from ordinary CI so a pull request cannot publish a release or force-push tags.
- [x] Run the appropriate Gradle compile/build task.
- [x] Run unit tests and relevant lint/static checks when available.
- [x] Use debug builds for routine CI where signing secrets are not required.
- [x] Keep release signing/publishing behind explicit trusted/manual conditions.
- [x] Upload artifacts only when useful; never expose secrets or signing material.
- [x] Verify the workflow on a test branch/PR before relying on it.

**Done when:** pushes and pull requests receive automated build/test feedback, and normal CI cannot publish releases.

## Phase 6 — Optional improvements after P0/P1 are stable

### 6.1 Evaluate heartbeat monitoring

- [x] Decide whether Binder ping + real transaction checks miss a specific failure mode.
- [x] If needed, prototype a heartbeat with a documented timeout and low polling overhead.
- [x] Do not run two independent watchdog loops that can trigger recovery simultaneously.
- [x] Measure battery and reliability impact before enabling heartbeat by default.

### 6.2 Evaluate launch-mode recovery

- [x] Confirm exact launch modes supported by this fork before adding a recovery branch.
- [x] Current launch-mode annotation includes `UNKNOWN`, `ROOT`, and `ADB`; do not add Dhizuku/TCP recovery by assumption.
- [x] If a new mode is deliberately supported, define startup, stop, verification, permission, and failure behavior end-to-end.
- [x] Preserve existing TCP behavior rather than conflating it with ADB worker recovery.
- [x] Add tests and user-facing status/error handling for any newly supported mode.

### 6.3 Reliability observability

- [x] Ensure logs identify health result, launch mode, attempt number, and recovery outcome.
- [x] Prevent notification spam during repeated failure.
- [x] Make exhausted recovery visible without claiming the server recovered when verification failed.

## Final acceptance checklist

- [x] Existing UI/UX is unchanged except for a necessary, explicitly scoped reliability status/setting.
- [x] Package IDs, public APIs, and existing launch flows remain compatible.
- [x] Watchdog initialization and recovery are thread-safe.
- [x] Binder health checks are bounded and never block the main thread.
- [x] User-stop and expected-death protections work.
- [x] Recovery is serialized, has bounded retries/backoff, and is verified.
- [x] ADB unique work does not duplicate or cancel valid work unexpectedly.
- [x] Automated tests and CI pass; exact commands/results are reported.
- [x] No secrets, keystores, generated build outputs, or unrelated files are committed.
- [x] Implementation summary lists changed files, tests run, results, and known limitations.

## Suggested commit sequence

1. `watchdog: make initialization and recovery admission thread-safe`
2. `watchdog: bound binder health checks and fix retry accounting`
3. `adb: prevent duplicate recovery work`
4. `test: cover watchdog state and recovery decisions`
5. `ci: run build and tests on push and pull requests`
6. `docs: document watchdog recovery behavior`
