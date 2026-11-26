# UML kernel binaries — provenance

Debroid runs a real Linux kernel as an ordinary Android app process
(`ARCH=um SUBARCH=arm64`). The kernel is **not built in this repository** yet; the
APK ships pinned, prebuilt bionic binaries from upstream, and this file records
exactly which commit they came from so the app can never contain an anonymous
kernel.

| | |
|---|---|
| Upstream | [`zalexdev/linux-um-arm64`](https://github.com/zalexdev/linux-um-arm64), branch `um-arm64` |
| Commit | `a528aeed4c28` (kernel reports it in `uname`) |
| Release | [`prebuilt-20260816`](https://github.com/zalexdev/linux-um-arm64/releases/tag/prebuilt-20260816) |
| Licence | **GPL-2.0** (kernel). Source: the upstream repository at that commit. |
| Built for | bionic (Android), static, aarch64, 16 KB guest pages (runs on 4 KB hosts too) |

Files copied into the APK by `.github/workflows/debug-build.yml`, verifying the
SHA-256 of each download first:

| Asset | Installed as | SHA-256 |
|---|---|---|
| `linux` | `lib/arm64-v8a/libuml-kernel-16k.so` | `280be89df3bf6f3607fbbb993d3130399d90658c2ced13ececa1fd07dbe55845` |
| `stub_exe` | `lib/arm64-v8a/libstub_exe.so` | `83f51f7c45133daa135b595562b09c5e2829f1d0f1e00b7fce2a7695370781fc` |

They are ordinary static ELF executables in the APK's native library directory,
which is why the app uses `useLegacyPackaging = true`: Android extracts them, and
then `ProcessBuilder` can exec them directly. Nothing about this needs root,
`/dev/kvm`, a kernel module or a capability.

The guest configuration the kernel was built with (read out of the binary's
embedded `IKCONFIG`) includes `CONFIG_BLK_DEV_UBD=y`, `CONFIG_EXT4_FS=y`,
`CONFIG_DEVTMPFS=y`, `CONFIG_TMPFS=y`, `CONFIG_HOSTFS=y`, `CONFIG_NR_CPUS=1` and
no squashfs — that is why Debroid boots an **ext4** disk on `ubd0` rather than a
squashfs root.

Building the kernel from source in this repository's CI is on the roadmap; when
that happens this notice becomes a build recipe and the binaries stay byte-for-byte
reproducible from the pinned commit.
