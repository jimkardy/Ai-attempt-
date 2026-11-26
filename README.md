# Debroid

An Android app that runs a full **Debian** userland on a phone through **User-Mode Linux (UML)** —
no root, no emulator, no `proot`.

> ## ⚠️ NOT DONE. NOT STABLE. DO NOT TRUST IT WITH ANYTHING.
>
> 0.2.1 ships the VM manager: create up to two VMs with device-checked resources, start / stop /
> restart / delete them, and a terminal tab wired to the guest console. **The arm64 UML kernel and
> the Debian rootfs are not bundled yet**, so pressing Start tells you exactly which artefact is
> missing instead of pretending to boot. Expect breakage. Nothing here is production ready.

## Supported devices — arm64 only, Android 10–15

| | |
|---|---|
| CPU | **arm64-v8a only** (`aarch64`). 32-bit ARM, x86 and x86_64 are **not** supported and the APK refuses to install on them. |
| Android | **10 to 15** (API 29–35). Newer versions install but are untested. |
| Kernel | must be able to run User-Mode Linux. 4 KB and 16 KB page sizes are both handled; the Debug tab reports a best-effort `uml capability` line for your phone. |
| RAM | 2 GB minimum (3 GB+ recommended) |
| Storage | 6 GB free minimum |

## Changes in 0.2.1

- The VM tab now renders on first launch instead of staying blank until you switch tabs.
- Fixed the crash (`The specified child already has a parent`) when opening **Create VM**.
- The **Create VM** button is always available: greyed out while both VM slots are used, back to
  normal as soon as you delete one.

## What works in 0.2.1

| Feature | State |
|---|---|
| VM tab as the home page: docker-style cards, start / stop / restart / delete | ✅ |
| Create VM with device-bounded cores / RAM / disk + port forwards | ✅ |
| Hard limits: 2 VMs max, one running at a time, resources validated against free RAM/storage | ✅ |
| Persistence: VMs, sparse disk images and console logs survive restarts | ✅ |
| Terminal tab: Start button when idle, live guest console when running | ✅ (console is the supervisor's output; no guest yet) |
| Debug tab: one log panel, **COPY EVERYTHING** at the top, self-test, host shell, crash capture | ✅ |
| Booting Debian (UML kernel + rootfs) | ❌ next milestone |

## Branches

| Branch | Meaning |
|---|---|
| `main` | Stable only. Carries this notice + licence; nothing lands there until a debugging build has been installed and confirmed on a real phone. |
| `debugging` | All development. Install and test from here. |
| `updates` | Reserved for the rolling Debian/rootfs update channel. |

Releases on `debugging` are tagged `debug-v*`, marked **pre-release**, and signed with a throwaway
test key committed in `keystore/` so each build installs over the previous one. That key is not an
identity.

## Install a debug build

1. Open the latest `debug-v*` pre-release on the **Releases** page.
2. Download `app-debug.apk`.
3. Allow "install unknown apps" for your browser/file manager, tap the APK, accept.

Play Protect will warn about an unknown developer — expected for sideloaded builds. If a build
refuses to install over an older one, uninstall once and install again.

## Using it

**VM tab (home)** — `Create VM` opens a form whose limits come from your phone: cores up to the CPU
count, RAM up to what is free minus a reserve for Android and the app, disk up to free storage minus
2 GB. There are no free-text sizes, so you cannot starve the device. Two VMs max; each card has
Start, Restart and Delete, and starting a second VM stops the first (there is one console).

**Terminal tab** — nothing running: a Start button. Running: the guest console, with an input line,
Restart, Stop and Copy log.

**Debug tab** — one panel with every log, a **COPY EVERYTHING** button at the top (device, support,
self-test, VM consoles, every channel), plus Share, ZIP, Issue, Clear and a diagnostic host shell.
Paste that report into an issue and the bug is usually obvious.

## Build

```bash
# JDK 17 + Android SDK (platform 35, build-tools 35.0.0)
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug
```

CI (`.github/workflows/debug-build.yml`) builds every push to `debugging`, compiles the tiny
`native/abi-gate/abi_gate.c` marker into `lib/arm64-v8a/libdebroid-abi.so` (this is what makes the APK
refuse other ABIs), verifies the APK with `apksigner` / `zipalign` / `aapt2`, uploads it and updates
the matching `debug-v*` pre-release.

>A local build without the NDK produces an APK with no native library, so it is not ABI-gated. Only
>the CI-published APK carries the arm64-only restriction.

## Roadmap

1. **0.1.x** — installable diagnostics shell. ✅
2. **0.2.0** — VM manager, bounded resources, console UI, arm64-only build. ✅
3. **0.3.0** — arm64 UML kernel built on GitHub's native arm64 runners from
   [`zalexdev/linux-um-arm64`](https://github.com/zalexdev/linux-um-arm64), Debian `trixie` squashfs,
   real boot, SSH.
4. **0.4.0** — rolling "always latest Debian" rootfs updates on the `updates` branch.

## Licence

**MIT** — see [LICENSE](LICENSE). No warranty, which matters because it does not work yet.

Third-party notes:

- The UML kernel is built from [`zalexdev/linux-um-arm64`](https://github.com/zalexdev/linux-um-arm64),
  which is **GPL-2.0**. Kernel binaries and patches stay GPL-2.0 even though the app code is MIT.
- Podroid ([ExTV/Podroid](https://github.com/ExTV/Podroid), GPL-2.0) is **inspiration only** — no
  Podroid source is copied into this repository.
- Android SDK/NDK and Gradle are used under their own licences; nothing proprietary is vendored.

`keystore/debroid-test.keystore` is a throwaway test key (password `debroidtest`) whose only purpose
is making debug builds upgradeable. It grants no identity.
