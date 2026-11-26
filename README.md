# Debroid

An Android app that runs a full **Debian** userland on the phone through **User-Mode Linux (UML)** —
no root, no emulator, no `proot`, a real Linux kernel in userspace.

> **Status: v1 — diagnostics build.**
> This version ships the parts that make the project debuggable: a real Debug tab, crash capture,
> self-test, host shell terminal and persisted logs. The arm64 UML kernel and the Debian squashfs
> are produced by CI in the next milestone; until then the VM tab reports exactly what is missing
> instead of failing.

## Branches

| Branch | Meaning |
|---|---|
| `main` | Stable only. Nothing lands here until a debugging build has been installed and confirmed on a real phone. |
| `debugging` | Every build I produce. Install from here, test, report. |
| `updates` | Reserved for the rolling Debian/rootfs update channel. |

Releases on `debugging` are tagged `debug-v*` and marked **pre-release**. They are signed with a
throwaway test key committed in `keystore/` so that each new build installs *over* the previous one
instead of failing with "App not installed". That key is not an identity, do not trust it.

## Install (debug builds)

1. Open the latest `debug-v*` pre-release on this repo's **Releases** page.
2. Download `app-debug.apk`.
3. Allow "install unknown apps" for the browser/file manager you use, tap the APK, accept.
4. Play Protect may warn about an unknown developer — that is expected for sideloaded builds.

Requirements: Android 8.0+ (API 26), any ABI (v1 has no native code), ~40 MB free space.

If a build refuses to install over an older one, uninstall the old app once
(`Settings → Apps → Debroid → Uninstall`) and install again. From then on upgrades are clean.

## The Debug tab

Everything a maintainer would ask for, all local, all copyable:

- **Copy everything** — one tap puts the whole report (device, app, engine state, logs) on the clipboard.
- **Share** — sends the report through any app.
- **Save ZIP** — writes `debroid-logs-<timestamp>.zip` (report + every log channel) and opens the share sheet.
- **New issue** — copies the report and opens a pre-filled GitHub issue.

Sections: Device & app, Engine (UML), Self-test (PASS/FAIL checks for storage, shell, logcat, assets,
loopback, clipboard, page size, binaries), Logs (channel picker, live tail, copy, clear) and a
**Last session crashed** card with the captured stack trace.

Logs are kept in five rotating files under the app's private `files/logs` directory, so they survive
a crash, a force-stop and a reboot. Nothing is uploaded anywhere; sharing is always your action.

### Reporting a problem

1. Debug tab → **Run self-test**.
2. **Copy everything** (or **Save ZIP** if the report is huge).
3. Paste it into a new issue, or use **New issue** which pre-fills one.

If the app will not open at all, the crash is still saved: on the next launch the app shows
"Last session crashed" with a **Copy** button.

## Build

```bash
# JDK 17 + Android SDK (platform 35, build-tools 35.0.0)
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug
```

CI (`.github/workflows/debug-build.yml`) builds every push to `debugging`, verifies the APK with
`apksigner`/`zipalign`/`aapt2`, publishes it as an artifact and updates the `debug-v0.1.0` pre-release.

## Roadmap

1. **v1 (this build)** — installable shell: Debug tab, crash capture, self-test, host shell.
2. **v2** — arm64 UML kernel (`zalexdev/linux-um-arm64`) built by CI, supervisor, Debian squashfs,
   guest console, SSH, rootfs updater.

## Credits and licensing

- UML-on-ARM64 kernel work: [`zalexdev/linux-um-arm64`](https://github.com/zalexdev/linux-um-arm64) (GPL-2.0).
- Podroid ([ExTV/Podroid](https://github.com/ExTV/Podroid), GPL-2.0) is used as **inspiration only** —
  no Podroid source is copied into this repository.
- Terminal emulation: plain host shell for now; a proper emulator module lands with the guest console.

Code in this repository is released under **GPL-2.0**, matching the kernel and tooling it builds on.
The `keystore/debroid-test.keystore` file is a throwaway test key (password `debroidtest`) used only
to make debug builds upgradeable; it grants no identity and must never be used for anything else.
