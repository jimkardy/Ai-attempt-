# Debroid

An Android app that aims to run a full **Debian** userland on a phone through **User-Mode Linux (UML)** —
no root, no emulator, no `proot`.

> ## ⚠️ NOT DONE. NOT STABLE. DO NOT TRUST IT WITH ANYTHING.
>
> This is v1 of an unfinished experiment. It installs, it opens, and it gives you a full
> developer-grade diagnostics screen — that is all it does today. **There is no Debian guest yet.**
> The UML kernel, the supervisor and the rootfs are the next milestone. Expect missing features,
> expect breakage, expect the architecture to change. Nothing here is production ready.

## What actually works in v1

| Feature | State |
|---|---|
| Installs and launches on Android 8+ (arm64/arm/x86, no native code) | ✅ |
| **Debug tab**: device/app report, engine state, self-test, 6 live log channels, crash capture, copy/share/ZIP/issue export | ✅ |
| Logs survive crashes, force-stops and reboots (rotating files) | ✅ |
| Global crash handler + "Last session crashed" screen with a Copy button | ✅ |
| Terminal tab: interactive host shell (`/system/bin/sh`, app uid) | ✅ |
| **VM tab: boot the Debian guest** | ❌ not implemented — it reports exactly what is missing |
| UML kernel, supervisor, Debian squashfs, SSH, rootfs updater | ❌ planned for v2 |

## Branches

| Branch | Meaning |
|---|---|
| `main` | Stable only. Currently just this notice + licence. **Nothing lands here** until a debugging build has been installed and confirmed on a real phone. |
| `debugging` | All development. Install and test from here. |
| `updates` | Reserved for the rolling Debian/rootfs update channel. |

Releases on `debugging` are tagged `debug-v*`, marked **pre-release**, and signed with a throwaway
test key committed in `keystore/` so each build installs over the previous one instead of failing
with "App not installed". That key is not an identity — never trust it.

## Install a debug build

1. Open the latest `debug-v*` pre-release on the **Releases** page.
2. Download `app-debug.apk`.
3. Allow "install unknown apps" for your browser/file manager, tap the APK, accept.
4. Play Protect may warn about an unknown developer — expected for sideloaded builds.

Requirements: Android 8.0+ (API 26), any ABI, ~40 MB free space. If a build refuses to install over
an older one, uninstall once (`Settings → Apps → Debroid → Uninstall`) and install again.

## The Debug tab

Everything a maintainer would ask for, all local, all copyable:

- **Copy everything** — one tap puts the whole report (device, app, engine state, logs) on the clipboard.
- **Share** — sends the report through any app.
- **Save ZIP** — writes `debroid-logs-<timestamp>.zip` (report + every log channel) and opens the share sheet.
- **New issue** — copies the report and opens a pre-filled GitHub issue.

Sections: Device & app · Engine (UML) · Self-test (PASS/FAIL for storage, shell, logcat, assets,
loopback, clipboard, page size, binaries) · Logs (channel picker, live tail, copy, clear) ·
Last session crashed.

Logs live in five rotating files under the app's private `files/logs`, so they survive a crash, a
force-stop and a reboot. Nothing is uploaded; sharing is always your action.

### Reporting a problem

1. Debug tab → **Run self-test**.
2. **Copy everything** (or **Save ZIP** if the report is huge).
3. Paste it into a new issue — or use **New issue**, which pre-fills one.

If the app will not open at all, the crash is still saved; the next launch shows
"Last session crashed" with a **Copy** button.

## Build

```bash
# JDK 17 + Android SDK (platform 35, build-tools 35.0.0)
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug
```

CI (`.github/workflows/debug-build.yml`) builds every push to `debugging`, verifies the APK with
`apksigner` / `zipalign` / `aapt2`, uploads it as an artifact and updates the matching `debug-v*`
pre-release.

## Roadmap

1. **v1 (this build)** — installable shell: Debug tab, crash capture, self-test, host shell. ✅
2. **v2** — arm64 UML kernel (`zalexdev/linux-um-arm64`) built by CI, own supervisor, Debian
   squashfs, guest console, SSH on 9922.
3. **v3** — rolling "always latest Debian" rootfs updates on the `updates` branch.

## Licence

**MIT** — see [LICENSE](LICENSE). Do whatever you want with this code; it comes with no warranty,
which matters because it does not work yet.

Third-party notes:

- The future UML kernel is built from [`zalexdev/linux-um-arm64`](https://github.com/zalexdev/linux-um-arm64),
  which is **GPL-2.0**. Kernel binaries and patches stay GPL-2.0 even though the app code here is MIT.
- Podroid ([ExTV/Podroid](https://github.com/ExTV/Podroid), GPL-2.0) is used as **inspiration only** —
  no Podroid source is copied into this repository.
- Android SDK/NDK and Gradle are used under their own licences; nothing proprietary is vendored.

`keystore/debroid-test.keystore` is a throwaway test key (password `debroidtest`) whose only purpose
is making debug builds upgradeable. It grants no identity.
