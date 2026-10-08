<div align="center">
   
# Shizuku (Zuher5 Fork)

A reliability-focused fork of [thedjchi/Shizuku](https://github.com/thedjchi/Shizuku) with enhanced server lifecycle management, intelligent watchdog recovery, and refined OLED dark mode styling.

[![Latest Release](https://img.shields.io/github/v/release/zuher5/Shizuku?style=for-the-badge&color=3060bf&labelColor=204080&label=Release)](https://github.com/zuher5/Shizuku/releases/latest)
[![Build Status](https://img.shields.io/github/actions/workflow/status/zuher5/Shizuku/app.yml?branch=master&style=for-the-badge&label=Build)](https://github.com/zuher5/Shizuku/actions)
[![License](https://img.shields.io/github/license/zuher5/Shizuku?style=for-the-badge&color=4caf50&labelColor=2e7d32)](LICENSE)

</div>

---

## ⚠️ Disclaimer & Lineage

This is a **personal reliability fork** of Shizuku.
* Upstream base: [thedjchi/Shizuku](https://github.com/thedjchi/Shizuku)
* Original upstream: [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku)

This fork retains the exact UI/UX, features, and API compatibility of `thedjchi/Shizuku`, while hardening server resilience and background recovery under modern aggressive Android ROMs.

---

## ⬇️ Download

Download the latest signed release APK from [GitHub Releases](https://github.com/zuher5/Shizuku/releases).

- **Current Release:** `shizuku-v13.7.0-Zuher5.apk`
- Signed permanently with a dedicated release keystore for seamless updates.

---

## 🚀 Key Improvements in this Fork

### 🛡️ 1. Resilient Watchdog & Lifecycle Architecture
- **Atomic State Machine (`ShizukuStateMachine`):** Lifecycle states (`STARTING`, `RUNNING`, `STOPPING`, `STOPPED`, `CRASHED`) are tracked via atomic CAS transitions to eliminate race conditions.
- **Decoupled Architecture:** Clean separation of responsibilities:
  - `ShizukuStateMachine`: owns state state-transitions and reactive flow.
  - `WatchdogManager`: orchestrates recovery policies, locks, and cooldowns.
  - `WatchdogService`: lightweight Android foreground service handling health observation.
- **Zombie Binder Detection:** Health checks verify not just `pingBinder()`, but active IPC transactions. If a deadlocked/zombie binder is detected, recovery triggers automatically.
- **Single-Flight Recovery Guard:** Atomic locks prevent recovery collisions from concurrent Binder deaths, health timers, and boot receivers.
- **Monotonic Cooldown & Backoff:** Uses `SystemClock.elapsedRealtime()` for drift-free restart pacing, avoiding aggressive loops and saving battery.
- **Intentional Stop Protection:** Explicit user stops and intentional restarts carry a bounded expiration to suppress false crash alarms.
- **Lockscreen-Aware ADB Recovery:** Waits for `USER_PRESENT` before attempting wireless ADB restart when the device is locked.

### 📶 2. Network & Hostile ROM Hardening
- **`AdbNetworkObserver`:** Reactive network callback monitoring unmetered Wi-Fi connectivity with debounce protection.
- **`WifiDebugReassert`:** Automatically re-asserts `adb_wifi_enabled` (0 → 1) upon Wi-Fi reconnection on hostile Android ROMs (such as MIUI / HyperOS, ColorOS, OxygenOS) that silently disable wireless debugging in the background.

### 🎨 3. Refined OLED Dark Mode & Card Depth
- **True OLED Black Theme:** Pure `#000000` background for AMOLED/OLED battery savings.
- **Natural Material 3 Filled Card Fade:** Restored soft grey container background (`#1E2026`) that smoothly contrasts against OLED black without harsh borders (`strokeWidth=0dp`) or abrupt drop-shadow cutoffs (`elevation=0dp`), matching the natural depth of Material 3.

### ⚙️ 4. Lightweight & Bloat-Free
- No analytics, tracking, or telemetry.
- Zero extra heavy dependencies; strictly standard library, native Android APIs, and existing project dependencies.
- Sub-4MB APK size with all native architectures included (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`).

---

## 📋 Features from thedjchi Base

- **TCP mode (`adb tcpip`):** Once started after reboot over Wi-Fi, service can be stopped and restarted without requiring an active Wi-Fi connection.
- **Start on boot:** Background initialization for root or wireless debugging.
- **Stealth mode:** Conceal Shizuku from detection on compatible environments.
- **TV & Large Screen Support:** D-Pad navigation support for Android TV / Google TV.
- **MediaTek Fixes:** Preserved critical compatibility fixes for MediaTek devices.
- **Automation Intents:** Start/stop intents for Tasker, MacroDroid, and Automate.

---

## 📱 Requirements

- **Android 7.0+** (API 24+)
- **Root Mode:** Requires root privileges (Magisk / KernelSU / APatch)
- **Wireless Debugging Mode:** Android 11+
- **USB ADB Mode:** Any supported Android version via PC

---

## 🛠️ Building

Application builds are managed authoritatively via **GitHub Actions** CI workflows to ensure reproducible builds and consistent release signing.

For local development and unit tests:
```bash
git clone --recurse-submodules https://github.com/zuher5/Shizuku.git
cd Shizuku
./gradlew test
```

---

## 📃 License

Licensed under the [Apache License, Version 2.0](LICENSE).
