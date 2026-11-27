package com.aiattempt.debroid.vm

import android.content.Context
import com.aiattempt.debroid.BuildConfig
import com.aiattempt.debroid.log.Channel
import com.aiattempt.debroid.log.LogStore
import com.aiattempt.debroid.sys.SysInfo
import java.io.File
import java.io.OutputStream

/**
 * The UML supervisor. It launches the arm64 kernel straight out of
 * `nativeLibraryDir` (that is why the APK uses legacy JNI packaging), attaches
 * the guest console to the VM's console log and reports the exit code back.
 *
 * The kernel is a real Linux kernel running as an ordinary app process: no root,
 * no /dev/kvm, no hypervisor. `libuml-kernel-16k.so` is built from
 * [zalexdev/linux-um-arm64](https://github.com/zalexdev/linux-um-arm64) (GPL-2.0)
 * for 16 KB guest pages, which also runs on 4 KB hosts.
 *
 * The guest root is the VM's own ext4 disk image (`vms/<id>/disk.img`) on UML's
 * `ubd0` block device, and `native/rootfs/debroid-init` mounts the pseudo
 * filesystems and hands the console to a shell.
 */
class VmProcess private constructor(
    val vmId: String,
    private val process: Process,
    private val stdin: OutputStream?
) {
    val isAlive: Boolean get() = runCatching { process.isAlive }.getOrDefault(false)

    fun sendLine(line: String): Boolean = try {
        val out = stdin ?: return false
        out.write((line + "\n").toByteArray())
        out.flush()
        true
    } catch (_: Throwable) {
        false
    }

    fun terminate() {
        runCatching { stdin?.close() }
        runCatching { process.destroy() }
    }

    fun kill() {
        runCatching { process.destroyForcibly() }
    }

    sealed class LaunchResult {
        data class Started(val process: VmProcess) : LaunchResult()
        data class Missing(val reason: String) : LaunchResult()
    }

    companion object {

        const val KERNEL_4K = "libuml-kernel-4k.so"
        const val KERNEL_16K = "libuml-kernel-16k.so"
        const val STUB = "libstub_exe.so"

        /** Kernel binaries live in the APK's native library directory. */
        fun kernelPath(ctx: Context): File? {
            val dir = File(ctx.applicationInfo.nativeLibraryDir)
            return preferredKernelNames().map { File(dir, it) }.firstOrNull { it.exists() }
        }

        private fun preferredKernelNames(): List<String> =
            if (SysInfo.pageSizeKb() == 16) listOf(KERNEL_16K, KERNEL_4K) else listOf(KERNEL_4K, KERNEL_16K)

        /** UML execs this helper to hold a guest address space. */
        fun stubPath(ctx: Context): File? =
            File(ctx.applicationInfo.nativeLibraryDir, STUB).takeIf { it.exists() }

        fun diskPath(ctx: Context, vm: VmInstance): File = VmStore.diskImage(ctx, vm.id)

        /** The exact argv, also written to the Engine log so a failure is reproducible. */
        fun arguments(ctx: Context, vm: VmInstance): List<String> {
            val kernel = kernelPath(ctx)
            val stub = stubPath(ctx)
            val disk = diskPath(ctx, vm)
            return buildList {
                add(kernel?.absolutePath ?: "<missing $KERNEL_16K>")
                add("mem=${vm.ramMb}M")
                // No `umid`: UML names its state files after it and refused the
                // short name ("umid_file_name : buffer too short"). Only one VM
                // runs at a time and TMPDIR points at this VM's own directory,
                // so the default identity is both quieter and safer.
                add("ubd0=${disk.absolutePath}")
                add("root=/dev/ubda")
                add("rw")
                add("rootfstype=ext4")
                add("init=/sbin/debroid-init")
                add("stub_exe=${stub?.absolutePath ?: "<missing $STUB>"}")
                // `seccomp=on` cannot work inside an Android app: the port's
                // SECCOMP mode installs its own BPF filter, and the `seccomp()`
                // syscall is not in bionic's app allowlist, so Android traps it
                // with SIGSYS. The 0.3.3 console died at "Checking that seccomp
                // filters can be installed..." for exactly that reason. The
                // ptrace userspace is the only mode that can run here.
                add("seccomp=off")
                add("con=null")
                add("con0=fd:0,fd:1")
                add("panic=-1")
                // Debug-tab escape hatch: extra UML parameters, appended last so
                // they win over the defaults above.
                addAll(KernelArgs.extra(ctx))
            }
        }

        fun missingArtefacts(ctx: Context, vm: VmInstance): List<String> = buildList {
            if (kernelPath(ctx) == null) add("UML kernel (${preferredKernelNames().joinToString(" or ")})")
            if (stubPath(ctx) == null) add("UML stub helper ($STUB)")
            if (!diskPath(ctx, vm).exists()) add("Debian disk image (vms/${vm.id}/disk.img)")
        }

        fun launch(
            ctx: Context,
            vm: VmInstance,
            onLine: (String) -> Unit,
            onExit: (Int) -> Unit
        ): LaunchResult {
            val kernel = kernelPath(ctx)
                ?: return LaunchResult.Missing(
                    "The arm64 UML kernel is missing from this install " +
                        "(${preferredKernelNames().joinToString(" / ")}). Reinstall the APK from the " +
                        "debug-v${BuildConfig.VERSION_NAME} release — the kernel ships inside it."
                )
            val stub = stubPath(ctx)
                ?: return LaunchResult.Missing(
                    "The UML stub helper ($STUB) is missing from this install. Reinstall the APK."
                )
            val disk = diskPath(ctx, vm)
            if (!disk.exists() || disk.length() == 0L) {
                return LaunchResult.Missing(
                    "The Debian disk image for this VM is missing (${disk.absolutePath}). " +
                        "Press Start again so Debroid can fetch it."
                )
            }

            val command = arguments(ctx, vm)
            LogStore.log(Channel.ENGINE, "launch ${vm.name}: ${command.joinToString(" ")}")
            LogStore.log(
                Channel.ENGINE,
                "cwd=${VmStore.vmDir(ctx, vm.id).absolutePath} kernel=${kernel.length()} " +
                    "stub=${stub.length()} disk=${disk.length()} pages=${SysInfo.pageSizeKb()}K"
            )
            return try {
                val dir = VmStore.vmDir(ctx, vm.id)
                val builder = ProcessBuilder(command)
                    .directory(dir)
                    .redirectErrorStream(true)
                val env = builder.environment()
                env["HOME"] = dir.absolutePath
                env["TMPDIR"] = dir.absolutePath
                env["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
                env["TERM"] = "xterm-256color"
                val process = builder.start()
                LogStore.log(Channel.ENGINE, "kernel process spawned (${command.first()})")

                Thread({
                    try {
                        process.inputStream.bufferedReader().use { reader ->
                            while (true) {
                                val line = reader.readLine() ?: break
                                onLine(line)
                            }
                        }
                    } catch (t: Throwable) {
                        LogStore.logStack(Channel.ENGINE, "console reader stopped", t)
                    }
                }, "uml-${vm.id.take(6)}").apply { isDaemon = true; start() }

                Thread({
                    val code = try {
                        process.waitFor()
                    } catch (t: Throwable) {
                        LogStore.logStack(Channel.ENGINE, "waiting for the guest failed", t)
                        -1
                    }
                    LogStore.log(Channel.ENGINE, "guest ${vm.name} exited with $code")
                    onExit(code)
                }, "uml-wait-${vm.id.take(6)}").apply { isDaemon = true; start() }

                LaunchResult.Started(VmProcess(vm.id, process, process.outputStream))
            } catch (t: Throwable) {
                LogStore.logStack(Channel.ENGINE, "could not start the UML kernel", t)
                LaunchResult.Missing(
                    "Could not launch the UML kernel: ${t.javaClass.simpleName}: ${t.message}"
                )
            }
        }

        /** Everything the Debug report needs to explain a boot that did not happen. */
        fun describe(ctx: Context, vm: VmInstance?): String {
            val kernel = kernelPath(ctx)
            val stub = stubPath(ctx)
            val dir = File(ctx.applicationInfo.nativeLibraryDir)
            return buildString {
                appendLine("kernel       : ${kernel?.absolutePath ?: "MISSING"}" +
                    (kernel?.let { " (${it.length()} bytes)" } ?: ""))
                appendLine("stub helper  : ${stub?.absolutePath ?: "MISSING"}" +
                    (stub?.let { " (${it.length()} bytes)" } ?: ""))
                appendLine("native libs  : $dir")
                appendLine("guest pages  : ${SysInfo.pageSizeKb()} KB host")
                if (vm != null) {
                    val disk = diskPath(ctx, vm)
                    appendLine("disk image   : ${disk.absolutePath} — " +
                        if (disk.exists()) "${disk.length() / (1024 * 1024)} MB" else "missing")
                    appendLine("argv         : ${arguments(ctx, vm).joinToString(" ")}")
                appendLine("extra args   : ${KernelArgs.extra(ctx).joinToString(" ").ifBlank { "(none)" }}")
                }
            }
        }
    }
}
