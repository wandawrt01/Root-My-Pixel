# Root My Pixel

**Root My Pixel** is an Android application designed to automate root access on **Google Pixel** devices leveraging the **NebuSec IonStack** exploit (CVE-2026-43499) and integrating **ReSukiSU / KernelSU**.

---

## How the Application Works

Root My Pixel lets you *temporarily* gain root access with ReSukiSU in just one tap.

### Installation Workflow

1. **Device Detection & Profiling**
   - At startup, the app uses native JNI (`NativeProbe`), `/proc/version` queries, and system properties to detect the device codename, kernel version, CPU ABI, memory page size, and build display ID.
   - Via `ResolveTargetUseCase`, it matches the device details against supported target profiles defined in `assets/profiles.json`.

2. **Execution Mode** (toggle on the main screen, default off)
   - **App domain** (default): the helper is executed straight out of `nativeLibraryDir` (an app uid may exec there, but not in shell-owned `/data/local/tmp`) and dlopens the payload from `filesDir`. Nothing in the chain needs shell: the root child is forked from the payload process *before* its credentials are patched, so it captures the app uid, and the su daemon authorises that uid alongside `RMG_APP_UID`. Everything after the cred patch runs as root with SELinux permissive.
   - **Shizuku**: with the toggle on, the app uses **Shizuku** (UID 2000) to acquire ADB shell privileges, stages the payload and helper in `/data/local/tmp`, and binds a managed `ExploitService` via Binder IPC to stream logs to the UI.

3. **Exploit Payload Extraction & Execution**
   - Precompiled binary payloads (`.so`) corresponding to each supported build and the native helper tool (`libcve43499root.so`) are extracted from APK assets — to `/data/local/tmp` in Shizuku mode, or to the app's own `filesDir` in app-uid mode.
   - The IonStack exploit (CVE-2026-43499) is executed to establish a local root daemon socket (`temp_su.sock`), acquiring full `root` privileges.

4. **KernelSU / ReSukiSU Integration**
   - Staging of the `ksud` binary matching the device's Kernel Module Interface (KMI, e.g., `android15-6.6`).
   - The app triggers the KernelSU **late-load** mechanism (`ksud late-load --kmi <kmi>`).
   - Verifies KernelSU active status by probing kernel device nodes (`/dev/kernelsu`, `/sys/kernel/kernelsu`, `/data/adb/ksu`).

5. **User Interface & Management Tools**
   - Real-time live log progress monitoring.
   - Handy actions for **Soft Reboot** (restarting `system_server`) and **Log Exporting** for debugging purposes.

---

## Supported Devices & Build Profiles

| Device                | Codename   | Supported Builds   | Kernel KMI      | Tested |
|:----------------------|:-----------|:------------------|:----------------|:--------|
| **Pixel 10**          | `frankel`  | `CP2A.260705.006` | `android15-6.6` | ✅      |
| **Pixel 10 Pro**      | `blazer`   | `CP2A.260705.006` | `android15-6.6` | ✅      |
| **Pixel 10 Pro XL**   | `mustang`  | `CP2A.260705.006` | `android15-6.6` | ✅      |
| **Pixel 10 Pro Fold** | `rango`    | `CP2A.260705.006` | `android15-6.6` | ✅      |
| **Pixel 10a**         | `stallion` | `CP2A.260705.006` | `android15-6.6` | ⏳      |
| **Pixel 9 Pro Fold**  | `comet`    | `CP2A.260705.006` | `android15-6.1` | ✅      |
| **Pixel 9 Pro**       | `caiman`   | `CP2A.260705.006` | `android15-6.1` | ✅      |
| **Pixel 9 Pro XL**    | `komodo`   | `CP2A.260705.006` | `android15-6.1` | ✅      |
| **Pixel 9**           | `tokay`    | `CP2A.260705.006` | `android14-6.1` | ✅      |
| **Pixel 9a**          | `tegu`     | `CP2A.260705.006` | `android14-6.1` | ✅      |
| **Pixel 8 Pro**       | `husky`    | `CP2A.260705.006` | `android14-6.1` | ✅      |
| **Pixel 8**           | `shiba`    | `CP2A.260705.006` | `android14-6.1` | ✅      |
| **Pixel 8a**          | `akita`    | `CP2A.260805.005` | `android14-6.1` | ✅      |
| **Pixel 7a**          | `lynx`     | `CP2A.260705.006` | `android14-6.1` | ✅      |
| **Pixel 7 Pro**       | `cheetah`  | `CP2A.260705.006` | `android14-6.1` | ✅      |
| **Pixel 7**           | `panther`  | `CP2A.260705.006` | `android14-6.1` | ✅      |
| **Pixel 6a**          | `bluejay`  | `CP2A.260705.006`<br>`CP1A.260405.005` | `android14-6.1` | ✅      |
| **Pixel 6**           | `oriole`   | `CP2A.260705.006` | `android14-6.1` | ✅      |

---

## Prerequisites

1. A supported Google Pixel device listed in the table above.
2. **ReSukiSU Manager** installed on the device to manage root permissions granted to apps.
3. *Optional:* **Shizuku** installed and running via ADB (`adb shell sh /sdcard/Android/data/rikka.shizuku/starter.sh` or Wireless Debugging), if you enable the Shizuku toggle. Shizuku is the more heavily tested path; in the default app-domain mode keep the app in the foreground for the whole run, since the payload is a child of the app process and is subject to app lifecycle.

---

## Building from Source

To compile the entire project (native helper, exploit payloads for all targets, and the final debug APK):

### Build Requirements
- Android NDK r25+ (`ANDROID_NDK_HOME` set or present in Android SDK)
- macOS (arm64/x86_64) or Linux (x86_64) host
- Java 17+ and Gradle Wrapper

### Build Command
```bash
./build-all.sh
```

The compiled APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

To install it on a connected device via ADB:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Credits

- Exploit: [NebuSec IonStack](https://github.com/NebuSec/CyberMeowfia)
- App architecture: Inspired and adapted from [Root My Galaxy](https://github.com/BuSung-dev/Root-My-Galaxy)
- ReSukiSU (https://github.com/ReSukiSU/ReSukiSU)
