# Debroid — NOT DONE, NOT STABLE

**Status: work in progress. This branch is a placeholder, not a release.**
There is no working Debian guest here yet, and nothing in this repository should be treated as
stable, finished, or safe to rely on.

Debroid is an Android app that runs a full **Debian** userland on a phone through **User-Mode Linux
(UML)** — no root, no emulator, no `proot`. That is the goal. Today the VM manager, the storage and
the terminal UI exist; the UML kernel and the Debian rootfs are the next milestone.

## Supported devices — arm64 only, Android 10–15

| | |
|---|---|
| CPU | **arm64-v8a only** (`aarch64`). 32-bit ARM, x86 and x86_64 are **not** supported — the APK refuses to install on them. |
| Android | **10 to 15** (API 29–35). Newer versions install but are untested. |
| Kernel | must support **User-Mode Linux**. 4 KB and 16 KB page sizes are both handled; the app's Debug tab reports a best-effort `uml capability` line for your specific phone. |
| RAM | 2 GB minimum, 3 GB+ recommended. |
| Storage | 6 GB free minimum. |

Debroid cannot promise UML works on every arm64 phone: that depends on the kernel version, SELinux
policy and OEM restrictions. The app probes for it and tells you what it finds instead of failing
silently.

## Where the code actually is

| What | Where |
|---|---|
| All development builds and source | branch **`debugging`** |
| Installable test APKs | **Releases** → `debug-v*` (marked pre-release) |
| This branch (`main`) | Stable only. Nothing is promoted here until a build has been installed and confirmed on a real phone. Right now that is only this notice and the licence. |

The current build (`debug-v0.2.x`) is the **VM manager**: create up to two VMs with resources bounded
by your device (cores from the CPU count, RAM from free memory, disk from free storage), start / stop
/ restart / delete them, port forwards, a persistent console per VM, a Terminal tab that shows a
Start button or the live guest console, and a Debug tab with one log panel and a single
**COPY EVERYTHING** button. It does **not** boot Debian yet: pressing Start reports exactly which
artefact (arm64 UML kernel, Debian rootfs) is missing.

## What is not there yet

- [ ] arm64 UML kernel build (from `zalexdev/linux-um-arm64`)
- [ ] Debian `trixie` squashfs rootfs and its download/verify flow
- [ ] real guest boot, console input into the guest, SSH
- [ ] "always latest Debian" updater
- [ ] anything at all that could be called stable

## Licence

**MIT** — see [LICENSE](LICENSE). It comes with no warranty, which matters because it does not work
yet. The future UML kernel comes from `zalexdev/linux-um-arm64` and stays **GPL-2.0**; Podroid is
used as inspiration only, with no source copied.

## Trying it anyway

Install the newest `debug-v*` pre-release APK from the Releases page (arm64 Android 10–15 only), open
the **Debug** tab and tap **Copy everything** — that report is what makes bugs fixable. If the app
crashes, the crash is saved and shown on the next launch.
