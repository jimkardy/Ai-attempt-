# Debroid — NOT DONE, NOT STABLE

**Status: work in progress. This branch is a placeholder, not a release.**
There is no working Debian VM here yet, and nothing in this repository should be treated as
stable, finished, or safe to rely on.

Debroid is an Android app that aims to run a full **Debian** userland on a phone through
**User-Mode Linux (UML)** — no root, no emulator, no `proot`. That is the goal. Today only the
first, unfinished step exists.

## Where the code actually is

| What | Where |
|---|---|
| All development builds and source | branch **`debugging`** |
| Installable test APKs | **Releases** → `debug-v*` (marked pre-release) |
| This branch (`main`) | Stable only. Nothing is promoted here until a build has been installed and confirmed on a real phone. Right now that is only this notice and the licence. |

The first build (`debug-v0.1.x`) is a **diagnostics shell**: it installs, it opens, and it gives you
a full developer-grade Debug tab (device report, self-test, live logs, crash capture, copy/ZIP/issue
export) plus a host shell terminal. It does **not** boot Debian yet. The UML kernel, supervisor,
Debian rootfs, SSH and the rolling Debian updater are the next milestone.

## What is not there yet

- [ ] arm64 UML kernel build (from `zalexdev/linux-um-arm64`)
- [ ] VM supervisor in the app
- [ ] Debian squashfs rootfs
- [ ] guest console + SSH
- [ ] "always latest Debian" updater
- [ ] anything at all that could be called stable

## Licence

**MIT** — see [LICENSE](LICENSE). It comes with no warranty, which matters because it does not work
yet. The future UML kernel comes from `zalexdev/linux-um-arm64` and stays **GPL-2.0**; Podroid is
used as inspiration only, with no source copied.

## Trying it anyway

Install the newest `debug-v*` pre-release APK from the Releases page, then open the app's **Debug**
tab and tap **Copy everything** — that report is what makes bugs fixable. If the app crashes, the
crash is saved and shown on the next launch.
