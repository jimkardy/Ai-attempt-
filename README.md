# Debroid

An Android app that runs a full **Debian** userland on a phone through **User-Mode Linux (UML)** —
no root, no emulator, no `proot`.

> ## ⚠️ NOT DONE. NOT STABLE. DO NOT TRUST IT WITH ANYTHING.
>
> 0.3.0 boots a real Linux kernel: the APK ships the arm64 UML kernel and a Debian 13 (trixie)
> arm64 disk image is attached to the same `debug-v*` release, so **Start really starts a guest**.
> It is still early: one guest CPU, no guest networking yet, no SSH yet, and nothing has been
> tested by anyone but its author. Expect breakage.

## Supported devices — arm64 only, Android 10–15

| | | |
|---|---|---|
| CPU | **arm64-v8a only** (`aarch64`). 32-bit ARM, x86 and x86_64 are **not** supported and the APK refuses to install on them. |
| Android | **10 to 15** (API 29–35). Newer versions install but are untested. |
| Kernel | must be able to run User-Mode Linux. 4 KB and 16 KB page sizes are both handled; the Debug tab reports a best-effort `uml capability` line for your phone. |
| RAM | 2 GB minimum (3 GB+ recommended) |
| Storage | 6 GB free minimum (the Debian image itself is ~2 GB before compression) |
| Network | needed once, on the first Start, to fetch the Debian image |

## Changes in 0.3.1

- Pressing Start on a VM that has no disk image yet now hands the console to that VM immediately, so
  the Terminal tab shows the Debian image download instead of "No VM is running".
- Stop (or deleting the VM) during that download cancels it, and the guest no longer boots
  afterwards without being asked.

## Changes in 0.3.0

- **VMs boot.** The arm64 UML kernel (`zalexdev/linux-um-arm64`, GPL-2.0, pinned commit
  `a528aeed4c28`) is now inside the APK; Start runs a real Linux kernel as an ordinary app
  process — no root, no `/dev/kvm`, no hypervisor.
- **Debian disk image**: every new VM is cloned from a Debian 13 (trixie) arm64 ext4 image that is
  published with the APK and fetched once, on the first Start. The guest grows the filesystem to
  the disk size you reserved, at boot.
- **Edit button**: a small gear at the top right of each VM card opens an editor for name, cores,
  RAM, disk and port forwards — same device-bounded controls as the create form.
- **Reclaim Android storage** in that editor: runs `fstrim -v /` and clears Debroid's own caches,
  and reports exactly what Android answered (see the honest note below).
- **Much more logging**: the Engine channel records the full kernel argv, the artefacts and their
  sizes, the image download and the guest exit code.
- **Logs are capped at 100 MB** across the whole log directory; rotated files are dropped first.
- Every release now carries **the code too**: the tag's source archive *is* the code the APK was
  built from, plus `NOTICE.md` with the kernel commit and the SHA-256 of every pinned binary.

## What works in 0.3.0

| Feature | State |
|---|---|
| VM tab as the home page: docker-style cards, start / stop / restart / delete | ✅ |
| Create VM with device-bounded cores / RAM / disk + port forwards | ✅ |
| Edit an existing VM (gear) with the same bounds; disk can only grow | ✅ |
| Hard limits: 2 VMs max, one running at a time, resources validated against free RAM/storage | ✅ |
| Real boot: arm64 UML kernel + Debian trixie ext4 disk on `ubd0` | ✅ (single guest CPU; unverified networking) |
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
| `NOTICE.md` | where the kernel came from (commit + hashes) and its licence |
| *Source code (zip/tar.gz)* | GitHub's archive of the tag — the code this APK was built from |

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
support, UML artefacts, VM consoles, every channel, a `--- seccomp ---` section). At the bottom there
is one button for everyone else: **Having issues? Send the report to our team on GitHub** — it runs
the self-test, copies the full report and opens a new issue with it.

### About "Trim this VM's disk"

`fstrim` asks a filesystem to discard the blocks of files that were already deleted, so the device
can reuse them. On Android this is the wrong filesystem to trim: a 1 GB file you delete *inside* the
guest stays marked used in the VM's `disk.img` until the guest discards it.

So the trim runs **inside the guest**, over its console. UML's `ubd` driver has discard enabled by
default and turns each guest discard into a hole punch on `disk.img`, which is what makes Android see
the space again. The box reports Android's real usage of that disk before and after (`st_blocks`), and
also clears Debroid's own caches and interrupted downloads.

## Build

```bash
# JDK 17 + Android SDK (platform 35, build-tools 35.0.0)
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug
```

CI (`.github/workflows/debug-build.yml`) does the rest on every push to `debugging`:

1. builds the tiny `native/abi-gate/abi_gate.c` marker into `lib/arm64-v8a/libdebroid-abi.so`
   (this is what makes the APK refuse other ABIs),
2. downloads the pinned UML kernel and stub helper, verifying their SHA-256,
3. builds the APK and checks it with `apksigner` / `zipalign` / `aapt2`, including that the kernel
   is really inside,
4. builds the guest init (`native/init/debroid-init.c`) as a static aarch64 binary with the same
   pinned NDK, and verifies it has no interpreter,
5. builds the Debian trixie arm64 ext4 image on an arm64 runner with `debootstrap`, installing that
   exact init as `/sbin/debroid-init`,
6. publishes both, plus the init, checksums and `NOTICE.md`, as the `debug-v<version>` pre-release.

>A local build without the NDK produces an APK with no native library, so it is not ABI-gated and
>has no kernel. Only the CI-published APK carries both.

## Roadmap

1. **0.1.x** — installable diagnostics shell. ✅
2. **0.2.0** — VM manager, bounded resources, console UI, arm64-only build. ✅
3. **0.2.1** — VM tab renders on launch, create form no longer crashes, create button greys out. ✅
4. **0.3.0** — real boot: arm64 UML kernel + Debian trixie ext4 disk, per-VM edit sheet with storage
   reclaim, 100 MB log budget. ✅
5. **0.3.2** — static guest init, growable disk, trim inside the guest, exit-code diagnosis,
   one-button GitHub report. ✅
6. **0.3.3** — a debug field for extra kernel parameters. ✅ (its `seccomp=on` switch cannot work:
   Android traps the `seccomp()` syscall an app would need to install that filter.)
7. **0.3.4** — the kernel runs in the ptrace userspace again, and the report now names the exact
   syscall Android refuses, read from logcat's `crash` buffer before it rotates. 🔄
8. **0.4.0** — guest networking (`umnet`/`passt`), SSH, and the port forwards actually forwarding.
9. **0.5.0** — rolling "always latest Debian" rootfs updates on the `updates` branch, and building
   the kernel from source in this repository's own CI.

## Licence

**MIT** — see [LICENSE](LICENSE). No warranty, which matters because it does not work yet.

Third-party notes:

- The UML kernel is from [`zalexdev/linux-um-arm64`](https://github.com/zalexdev/linux-um-arm64),
  commit `a528aeed4c28`, and is **GPL-2.0**. Kernel binaries and patches stay GPL-2.0 even though
  the app code is MIT. Exact files and SHA-256s: [`native/uml/NOTICE.md`](native/uml/NOTICE.md).
- The Debian image is built from Debian trixie packages in CI; Debian is its own mix of licences,
  all of them free software.
- Podroid ([ExTV/Podroid](https://github.com/ExTV/Podroid), GPL-2.0) is **inspiration only** — no
  Podroid source is copied into this repository.
- Android SDK/NDK and Gradle are used under their own licences; nothing proprietary is vendored.

`keystore/debroid-test.keystore` is a throwaway test key (password `debroidtest`) whose only purpose
is making debug builds upgradeable. It grants no identity.
