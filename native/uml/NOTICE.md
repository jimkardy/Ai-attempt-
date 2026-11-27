# UML kernel binaries — provenance

Debroid runs a real Linux kernel as an ordinary Android app process
(`ARCH=um SUBARCH=arm64`). The kernel is **not built in this repository**; the APK
ships pinned, prebuilt bionic binaries, and this file records exactly which build
they came from so the app can never contain an anonymous kernel.

| | |
|---|---|
| Binary release | [`GlassOnTin/uml-transport`](https://github.com/GlassOnTin/uml-transport), tag `uml-guest-11` |
| Kernel source | [`zalexdev/linux-um-arm64`](https://github.com/zalexdev/linux-um-arm64), branch `um-arm64` (Linux 7.2-rc4), plus the three `uml-transport` patches |
| Licence | **GPL-2.0** (kernel). Source: the two repositories above. |
| Built for | bionic (Android), static, aarch64 |

This is the build that is known to boot a guest under an Android **app** sandbox.
The three patches matter:

1. `stub-execve-fallback.patch` — Android refuses to exec the SKAS stub from a
   memfd, so the kernel retries with a plain `execve()` on the path given by the
   `stub_exe=` kernel option.
2. `android-app-compat.patch` — the app seccomp policy traps `personality()`
   outright (`SECCOMP_RET_TRAP`, delivered with `force_sig_info`, so neither a
   handler nor ptrace can intervene). UML calls it on the re-exec-on-ASLR-change
   path while starting the guest init, which is exactly where 0.3.4 died
   (`Run /sbin/debroid-init as init process` → exit 159). The patch skips that
   path under `__ANDROID__` and installs a diagnostic handler that names a
   blocked syscall before the process dies.
3. `vector-napi-budget.patch` — network NAPI budget fix (unused until guest
   networking lands, but part of the same published build).

The linked libc is additionally neutered by
`tools/um-arm64/harness/patch-glibc-seccomp.py`: the `svc` after each
`mov x8, #99` (`set_robust_list`), `#293` (`rseq`) and `#436` (`close_range`)
site is replaced with a fake result, because the app seccomp policy force-kills
those syscalls and the static libc issues them at startup and on every thread
creation.

Files copied into the APK by `.github/workflows/debug-build.yml`, verifying the
SHA-256 of each download first:

| Asset | Installed as | SHA-256 |
|---|---|---|
| `libvmlinux.so` (91,523,968 bytes) | `lib/arm64-v8a/libuml-kernel.so` | `14d39961761cdbc9fe5478ae4881eb548d79d12720213d6b65e8385dcf6eee16` |
| `libuml-stub.so` (2,000 bytes) | `lib/arm64-v8a/libstub_exe.so` | `731c48d5bf42ef25e26625af6a02627a5d658bf9f67d6f7fc443d6d644d6e52e` |

The kernel artifact carries about 68 MB of DWARF; CI runs
`llvm-strip --strip-debug` on it, which takes it to roughly 9 MB and leaves the
loadable segment byte-identical. The hash above is of the **original** download.
CI then refuses to continue unless the stripped binary is static AArch64 and
contains zero unpatched `set_robust_list`/`rseq`/`close_range` syscall sites.

They are ordinary static ELF executables in the APK's native library directory,
which is why the app uses `useLegacyPackaging = true`: Android extracts them, and
then `ProcessBuilder` can exec them directly. Nothing about this needs root,
`/dev/kvm`, a kernel module or a capability.

The guest configuration the kernel was built with (readable from the binary's
embedded `IKCONFIG`) includes `CONFIG_BLK_DEV_UBD=y`, `CONFIG_EXT4_FS=y`,
`CONFIG_DEVTMPFS=y`, `CONFIG_TMPFS=y`, `CONFIG_HOSTFS=y`, `CONFIG_STATIC_LINK=y`
and `CONFIG_UML_NET_VECTOR=y` — which is why Debroid boots an **ext4** disk on
`ubd0` rather than a squashfs root.

Building the kernel from source in this repository's CI is on the roadmap; when
that happens this notice becomes a build recipe and the binaries stay reproducible
from the pinned source.
