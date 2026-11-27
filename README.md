# Debroid

An Android app that runs a full **Debian** userland on a phone through **User-Mode Linux (UML)** —
no root, no emulator, no `proot`.

> ## ⚠️ NOT DONE. NOT STABLE. DO NOT TRUST IT WITH ANYTHING.
>
> Start runs a real Linux kernel as an ordinary app process and boots a Debian 13 (trixie) arm64
> disk attached to the same `debug-v*` release. It is still early: one guest CPU, no guest
> networking, no SSH, and nothing has been tested beyond one family of phones. Expect breakage.

## Supported devices — arm64 only, Android 10–15

| | | |
|---|---|---|
| CPU | **arm64-v8a only** (`aarch64`). 32-bit ARM, x86 and x86_64 are **not** supported and the APK refuses to install on them. |
| Android | **10 to 15** (API 29–35). Newer versions install but are untested. |
| Kernel | must be able to run User-Mode Linux. 4 KB page sizes are the tested path; 16 KB is untested. The Debug tab reports a best-effort `uml capability` line for your phone. |
| RAM | 2 GB minimum (3 GB+ recommended) |
| Storage | 6 GB free minimum (the Debian image itself is ~2 GB before compression) |
| Network | needed once, on the first Start, to fetch the Debian image |

## Changes in 0.4.0

- **The boot failure is fixed at its cause.** 0.3.4 always died at the last startup line,
  `Run /sbin/debroid-init as init process`, with exit 159. That is exactly where UML calls
  `personality()` before handing over to the guest init — and Android's app seccomp policy traps
  that call with `SECCOMP_RET_TRAP` and `force_sig_info`, so no signal handler and no ptrace can
  intercept it. The APK now ships the arm64 UML kernel build that other Android projects use to
  boot real guests: same kernel source, plus the patches that skip the `personality()` re-exec path
  under Android, load the SKAS stub with a plain `execve()` (Android refuses memfd exec), and
  neuter the `set_robust_list`/`rseq`/`close_range` syscall sites that the app policy force-kills.
  CI now **fails the build** if an unpatched one of those sites is in the binary.
- **Two userspaces, one automatic retry.** Start uses `seccomp=on` (in-process syscall trapping).
  If a particular Android build refuses it with SIGSYS during startup, the VM is relaunched once in
  the `ptrace` userspace, which the sandbox always allows but which is slower. The console says
  which one is running.
- **Log capture is limited to this app.** It used to run `logcat -b all` — every app and system
  service on the phone. It is now filtered with `--uid=<our uid>`, so only Debroid's own processes,
  including the UML kernel process it spawns, can appear in the log panel, the report or a ZIP.
- **Nothing personal is baked into published files.** The app no longer hard-codes a repository
  path, and the debug report no longer includes one; the release location is supplied at build time.
  The Debug tab's bottom button runs the self-test, copies the report and opens the share sheet —
  it does not contact GitHub.

## What works

| Feature | State |
|---|---|
| VM tab as the home page: docker-style cards, start / stop / restart / delete | ✅ |
| Create VM with device-bounded cores / RAM / disk + port forwards | ✅ |
| Edit an existing VM (gear) with the same bounds; disk can only grow | ✅ |
| Hard limits: 2 VMs max, one running at a time, resources validated against free RAM/storage | ✅ |
| Real boot: arm64 UML kernel + Debian trixie ext4 disk on `ubd0` | ✅ (single guest CPU) |
| Terminal tab: Start button when idle, live guest console and an input line when running | ✅ |
| Debug tab: one log panel, **COPY EVERYTHING** at the top, self-test, host shell, crash capture | ✅ |
| Guest networking, SSH and port forwards actually doing something | ❌ next milestone |
| 16 KB page-size phones, Android 16 | untested |

## Branches

| Branch | Meaning |
|---|---|
| `main` | Stable only. Carries this notice + licence; nothing lands there until a debugging build has been installed and confirmed on a real phone. |
| `debugging` | All development. Install and test from here. Every release on it ships the APK **and** the exact code that produced it. |
| `updates` | Reserved for the rolling Debian/rootfs update channel. |

Releases on `debugging` are tagged `debug-v*`, marked **pre-release**, and signed with a throwaway
test key committed in `keystore/` so each build installs over the previous one. That key is not an
identity.

Each release contains:

| File | What it is |
|---|---|
| `app-debug.apk` | the app, with the UML kernel inside it |
| `apk.sha256` | its checksum |
| `debroid-rootfs-trixie-arm64.ext4.gz` | the Debian image the VMs are cloned from |
| `rootfs.sha256` | its checksum |
| `NOTICE.md` | where the kernel came from (build, patches, hashes) and its licence |
| *Source code (zip/tar.gz)* | the archive of the tag — the code this APK was built from |

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
Start, Restart, Delete and a **gear** for editing. Starting a second VM stops the first (there is
one console).

**Gear / edit sheet** — change the name, cores, RAM, disk and port forwards. Changes are saved
immediately and used on the next boot; the disk can only grow, because it already holds that
guest's filesystem. Raising the disk extends the sparse image at once and the guest grows its
filesystem to fill it on the next boot. The storage block shows the reserved size, the filesystem
inside the disk and what the disk really costs on Android, plus **Trim this VM's disk**.

**Terminal tab** — nothing running: a Start button. Running: the guest console, with an input line,
Restart, Stop and Copy log. The first Start of a new VM also downloads the Debian image, with
progress in the console.

**Debug tab** — one panel with every log and a **COPY EVERYTHING** button at the top (device,
support, UML artefacts, VM consoles, every channel, a `--- seccomp ---` section). At the bottom is
one button that runs the self-test, copies the report and opens the share sheet.

### About "Trim this VM's disk"

`fstrim` asks a filesystem to discard the blocks of files that were already deleted, so the device
can reuse them. On Android this is the wrong filesystem to trim: a 1 GB file you delete *inside* the
guest stays marked used in the VM's `disk.img` until the guest discards it.

So the trim runs **inside the guest**, over its console. UML's `ubd` driver has discard enabled by
default and turns each guest discard into a hole punch on `disk.img`, which is what makes Android see
the space again. The box reports Android's real usage of that disk before and after (`st_blocks`), and
also clears Debroid's own caches and interrupted downloads.

## Privacy

- Logcat is read with `--uid=<this app's uid>`, not the default firehose: other apps' and the
  system's log lines never enter Debroid's logs.
- The debug report contains hardware/kernel facts (useful for diagnosing a failed boot) and nothing
  that identifies an account. Copying it and sharing it is always an explicit action; the app does
  not upload anything by itself.
- The build contains no publisher account name: the release location is passed in at build time.

## Build

```bash
# JDK 17 + Android SDK (platform 35, build-tools 35.0.0, NDK 27)
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug -PdebroidReleaseRepo=<owner>/<repo>
```

CI (`.github/workflows/debug-build.yml`) does the rest on every push to `debugging`:

1. builds the static guest init (`native/init/debroid-init.c`) and the tiny
   `native/abi-gate/abi_gate.c` marker into `lib/arm64-v8a/libdebroid-abi.so` (this is what makes
   the APK refuse other ABIs),
2. downloads the pinned UML kernel and stub helper, verifies their SHA-256, strips the kernel's
   debug info and **gates the result**: static, AArch64, and no unpatched seccomp-killed syscall
   sites,
3. builds the APK and checks it with `apksigner` / `zipalign` / `aapt2`, including that the kernel
   is really inside,
4. builds the Debian trixie arm64 ext4 image on an arm64 runner with `debootstrap`, installing that
   exact init as `/sbin/debroid-init`,
5. publishes both, plus the init, checksums and `NOTICE.md`, as the `debug-v<version>` pre-release.

> A local build without the NDK produces an APK with no native library, so it is not ABI-gated and
> has no kernel. Only the CI-published APK carries both.

## Roadmap

1. **0.1.x** — installable diagnostics shell. ✅
2. **0.2.0** — VM manager, bounded resources, console UI, arm64-only build. ✅
3. **0.2.1** — VM tab renders on launch, create form no longer crashes, create button greys out. ✅
4. **0.3.0** — real boot: arm64 UML kernel + Debian trixie ext4 disk, per-VM edit sheet with storage
   reclaim, 100 MB log budget. ✅
5. **0.3.2** — static guest init, growable disk, trim inside the guest, exit-code diagnosis. ✅
6. **0.3.3/0.3.4** — extra kernel parameters field; diagnosis of the sandbox's SIGSYS deaths
   (exit 159) and the `crash`-buffer tombstone reader. ✅
7. **0.4.0** — the Android-compatible kernel build (the `personality()` re-exec fix), automatic
   `seccomp=on` → `ptrace` fallback, log capture limited to this app, no personal traces in
   published files. 🔄
8. **0.5.0** — guest networking (`umnet`/`passt`), SSH, and the port forwards actually forwarding.
9. **0.6.0** — rolling "always latest Debian" rootfs updates on the `updates` branch, and building
   the kernel from source in this repository's own CI.

## Licence

**MIT** — see [LICENSE](LICENSE). No warranty, which matters because it does not work yet.

Third-party notes:

- The UML kernel is GPL-2.0. The APK ships the pinned `uml-guest-11` bionic build from
  [`GlassOnTin/uml-transport`](https://github.com/GlassOnTin/uml-transport), whose source is the
  `um-arm64` branch of [`zalexdev/linux-um-arm64`](https://github.com/zalexdev/linux-um-arm64)
  plus the patches published in that transport repository. Kernel binaries and patches stay GPL-2.0
  even though the app code is MIT. Exact files, patch list and SHA-256s:
  [`native/uml/NOTICE.md`](native/uml/NOTICE.md).
- The Debian image is built from Debian trixie packages in CI; Debian is its own mix of licences,
  all of them free software.
- Podroid ([ExTV/Podroid](https://github.com/ExTV/Podroid), GPL-2.0) is **inspiration only** — no
  Podroid source is copied into this repository.
- Android SDK/NDK and Gradle are used under their own licences; nothing proprietary is vendored.

`keystore/debroid-test.keystore` is a throwaway test key (password `debroidtest`) whose only purpose
is making debug builds upgradeable. It grants no identity.
