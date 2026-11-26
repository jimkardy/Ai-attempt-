package com.aiattempt.debroid.vm

import android.content.Context
import com.aiattempt.debroid.sys.SysInfo
import java.io.File
import java.io.OutputStream

/**
 * The UML supervisor. It launches the arm64 kernel straight out of
 * `nativeLibraryDir` (that is why the APK uses legacy JNI packaging), wires the
 * guest console to the VM's console log and reports the exit code back.
 *
 * In 0.2.0 the kernel is not shipped yet, so [launch] returns
 * [LaunchResult.Missing] with the exact artefact that is absent. Nothing here
 * pretends a machine booted.
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

        /** Kernel binaries live in the APK's native library directory. */
        fun kernelPath(ctx: Context): File? {
            val dir = File(ctx.applicationInfo.nativeLibraryDir)
            val names = if (SysInfo.pageSizeKb() == 16) {
                listOf("libuml-kernel-16k.so", "libuml-kernel-4k.so")
            } else {
                listOf("libuml-kernel-4k.so", "libuml-kernel-16k.so")
            }
            return names.map { File(dir, it) }.firstOrNull { it.exists() }
        }

        fun rootfsPath(ctx: Context, vm: VmInstance): File =
            File(VmStore.vmDir(ctx, vm.id), "rootfs.squashfs")

        fun missingArtefacts(ctx: Context, vm: VmInstance): List<String> = buildList {
            if (kernelPath(ctx) == null) add("UML kernel (libuml-kernel-${if (SysInfo.pageSizeKb() == 16) "16k" else "4k"}.so)")
            if (!rootfsPath(ctx, vm).exists()) add("Debian rootfs (vms/${vm.id}/rootfs.squashfs)")
        }

        fun launch(
            ctx: Context,
            vm: VmInstance,
            onLine: (String) -> Unit,
            onExit: (Int) -> Unit
        ): LaunchResult {
            val kernel = kernelPath(ctx)
            if (kernel == null) {
                return LaunchResult.Missing(
                    "This build has no arm64 UML kernel bundled yet. The VM manager, storage, " +
                        "resource limits and console are real; the kernel and the Debian rootfs arrive " +
                        "in the next milestone, then this button boots the guest."
                )
            }
            val rootfs = rootfsPath(ctx, vm)
            if (!rootfs.exists()) {
                return LaunchResult.Missing(
                    "The Debian rootfs for this VM is missing (${rootfs.name}). Recreate the VM " +
                        "from the VM tab to fetch it again."
                )
            }
            return try {
                val dir = VmStore.vmDir(ctx, vm.id)
                val command = listOf(
                    kernel.absolutePath,
                    "mem=${vm.ramMb}M",
                    "root=/dev/root",
                    "rootfstype=squashfs",
                    "rootflags=${rootfs.absolutePath}",
                    "con=null",
                    "ssl=null",
                    "quiet"
                )
                val builder = ProcessBuilder(command)
                    .directory(dir)
                    .redirectErrorStream(true)
                val env = builder.environment()
                env["HOME"] = dir.absolutePath
                env["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
                env["TERM"] = "xterm-256color"
                val process = builder.start()

                Thread({
                    try {
                        process.inputStream.bufferedReader().use { reader ->
                            while (true) {
                                val line = reader.readLine() ?: break
                                onLine(line)
                            }
                        }
                    } catch (_: Throwable) {
                    }
                }, "uml-${vm.id.take(6)}").apply { isDaemon = true; start() }

                Thread({
                    val code = try {
                        process.waitFor()
                    } catch (_: Throwable) {
                        -1
                    }
                    onExit(code)
                }, "uml-wait-${vm.id.take(6)}").apply { isDaemon = true; start() }

                LaunchResult.Started(VmProcess(vm.id, process, process.outputStream))
            } catch (t: Throwable) {
                LaunchResult.Missing("Could not launch the UML kernel: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }
}
