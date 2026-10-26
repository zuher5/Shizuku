# AGENTS.md

Instructions for coding agents working in this repository.

## Never build locally

This repository is developed on an Android device through Termux. A Gradle or
Android build here exhausts the device: memory, storage and battery. Building
locally is not allowed, at any scope, including "just compiling", "a quick
syntax check", `:manager:compileDebugKotlin`, `:manager:testDebugUnitTest` and
anything that starts the Gradle daemon.

Do not install a JDK, the Android SDK, the NDK or the Gradle distribution to
enable a local build either.

**Build and test only on GitHub.** Push a branch and let CI do the work:

```sh
git push -u origin <branch>
gh run list  --repo zuher5/Shizuku --limit 3
gh run watch <run-id> --repo zuher5/Shizuku --exit-status
```

What CI covers, and where:

| Workflow | File | Runs |
| --- | --- | --- |
| `CI` | `.github/workflows/ci.yml` | `./gradlew testDebugUnitTest :manager:assembleDebug` on every push to `master`, `beta`, `feature/**`, `fix/**` and on pull requests |
| `Build App` | `.github/workflows/app.yml` | `workflow_dispatch` only; builds signed release, creates the tag and the GitHub release |

So a hand-written test is verified by pushing to a `fix/**` or `feature/**`
branch, and a device-installable APK comes from either CI's
`shizuku-debug-apk` artifact or a `Build App` dispatch.

Downloading a CI artifact for device testing is the one supported way to get a
build onto the phone:

```sh
gh run download <run-id> --repo zuher5/Shizuku -n shizuku-debug-apk -D ~/apk
cp ~/apk/*.apk /storage/emulated/0/Download/
```

## What local work is still fine

Reading and editing files, `git`, `gh`, `rg`/`grep`/`find`, `diff`, XML and
Gradle file edits, Python for the CI helper tests when a script's own unit tests
exist, and running those same tests directly:

```sh
python3 -m unittest discover -s tools/ci -p 'test_*.py'   # tools/ci tests only, no Android build
```

`git diff --check` before every commit. It catches whitespace damage that CI
would otherwise report as a failure on a file that was never compiled.

## Verification expectations

Because nothing compiles locally, a change is not proven until CI says so. Do not
claim a change works without a green run, and say plainly which parts CI cannot
reach: device behaviour, vendor ROM quirks, and anything in the release signing
path.

`TESTING.md` holds the manual, device-dependent scenarios. When a fix cannot be
covered by a unit test, add a scenario there instead of asserting it was tested.

## Repository conventions

- Branch for work; never commit directly to `master`. `ci.yml` runs on `fix/**`
  and `feature/**` pushes, which is what gives a branch its build.
- One concern per commit. Message prefixes in use: `fix(scope):`, `test(scope):`,
  `docs:`, `ci:`, `chore:`.
- Release: `Build App` (`workflow_dispatch`) builds signed, creates the tag and
  publishes. `VERSION`/`baseVersionName` in `build.gradle` is the version the
  artifact is named after.
- `shevery/` is a Git submodule. Do not edit it in place as part of a change to
  this repository.

## Known hazards in this tree

- `manager/src/main/java/moe/shizuku/manager/adb/AdbStarter.kt` has two entry
  points, `startAdb` and `start`. A host or parameter change must reach both, and
  `AdbStartWorker` and `StarterActivity` are its only callers.
- The wireless start path spans `AdbMdns` → `AdbDialogFragment` →
  `StarterActivity` → `AdbStarter`. Dropping a value in the middle of it is the
  bug class that `fix(wireless-discovery-host)` exists to fix; see
  `TESTING.md` scenarios 13–15.
- `Starter.waitForBinder` deliberately throws `StarterBinderTimeoutException`,
  not `java.util.concurrent.TimeoutException`, because the worker treats the
  latter as a retryable connection failure.
