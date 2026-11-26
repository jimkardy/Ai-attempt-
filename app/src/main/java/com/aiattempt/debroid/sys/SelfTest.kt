package com.aiattempt.debroid.sys

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import com.aiattempt.debroid.BuildConfig
import com.aiattempt.debroid.log.Channel
import com.aiattempt.debroid.log.LogStore
import com.aiattempt.debroid.vm.VmImage
import com.aiattempt.debroid.vm.VmManager
import com.aiattempt.debroid.vm.VmProcess
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Every environment check the app depends on, as plain text the user can paste
 * into an issue. Lines are [PASS] / [FAIL].
 */
object SelfTest {

    fun run(ctx: Context): String {
        val out = StringBuilder()
        val started = System.currentTimeMillis()
        out.appendLine("Debroid self-test — ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        out.appendLine("app ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) on Android ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}")
        out.appendLine()

        // 0. device support (arm64 only, Android 10-15)
        DeviceSupport.checks(ctx).forEach { c -> result(out, c.ok, c.name, c.detail) }
        out.appendLine("uml capability — ${DeviceSupport.umlCapability(ctx)}")
        out.appendLine()

        // 1. app storage
        try {
            val f = File(ctx.filesDir, "selftest.tmp")
            f.writeText("ok")
            val back = f.readText()
            f.delete()
            check("app internal storage writable", back == "ok")
            result(out, true, "app internal storage writable", ctx.filesDir.absolutePath)
        } catch (t: Throwable) {
            result(out, false, "app internal storage writable", "${t.javaClass.simpleName}: ${t.message}")
        }

        // 2. log directory
        try {
            val dir = File(ctx.filesDir, "logs")
            dir.mkdirs()
            val f = File(dir, "selftest.tmp")
            f.writeText("ok")
            f.delete()
            val files = dir.listFiles()?.size ?: 0
            result(out, true, "log directory", "${dir.absolutePath} ($files files)")
        } catch (t: Throwable) {
            result(out, false, "log directory", "${t.javaClass.simpleName}: ${t.message}")
        }

        // 3. host shell (diagnostic only, not the guest)
        try {
            val p = ProcessBuilder("/system/bin/sh", "-c", "cd \"${ctx.filesDir.absolutePath}\" && echo debroid_selftest_ok")
                .redirectErrorStream(true)
                .start()
            val text = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            result(out, text.contains("debroid_selftest_ok"), "host shell /system/bin/sh", text.take(120))
        } catch (t: Throwable) {
            result(out, false, "host shell /system/bin/sh", "${t.javaClass.simpleName}: ${t.message}")
        }

        // 4. logcat readable
        try {
            val p = ProcessBuilder("logcat", "-d", "-t", "5").redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            p.waitFor()
            result(out, text.isNotBlank(), "logcat readable", "${text.lines().size} lines")
        } catch (t: Throwable) {
            result(out, false, "logcat readable", "${t.javaClass.simpleName}: ${t.message}")
        }

        // 5. loopback networking
        try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { s ->
                result(out, s.localPort > 0, "loopback bind", "port ${s.localPort}")
            }
        } catch (t: Throwable) {
            result(out, false, "loopback bind", "${t.javaClass.simpleName}: ${t.message}")
        }

        // 6. clipboard (how logs get out)
        try {
            val cb = ctx.getSystemService(ClipboardManager::class.java)
            result(out, cb != null, "clipboard service", if (cb != null) "available" else "null")
        } catch (t: Throwable) {
            result(out, false, "clipboard service", "${t.javaClass.simpleName}: ${t.message}")
        }

        // 7. VM budget: the same limits the create form uses
        try {
            val budget = VmManager.budget(ctx)
            result(out, budget.reason == null, "vm budget", VmManager.budgetLine(ctx))
            if (budget.reason != null) out.appendLine("       ${budget.reason}")
        } catch (t: Throwable) {
            result(out, false, "vm budget", "${t.javaClass.simpleName}: ${t.message}")
        }

        // 8. VM inventory
        try {
            val vms = VmManager.list(ctx)
            result(out, vms.size <= VmManager.MAX_VMS, "vm count", "${vms.size} of ${VmManager.MAX_VMS}")
            vms.forEach { vm ->
                out.appendLine(
                    "       ${vm.name}: ${vm.state} · ${vm.specLine} · ${VmManager.isRunning(ctx, vm.id)}"
                )
            }
        } catch (t: Throwable) {
            result(out, false, "vm inventory", "${t.javaClass.simpleName}: ${t.message}")
        }

        // 9. engine artefacts: the kernel, the stub helper and every VM disk
        try {
            val kernel = VmProcess.kernelPath(ctx)
            result(
                out, kernel != null, "engine kernel",
                kernel?.let { "${it.name} (${it.length()} bytes)" } ?: "missing from nativeLibraryDir"
            )
            val stub = VmProcess.stubPath(ctx)
            result(
                out, stub != null, "engine stub helper",
                stub?.let { "${it.name} (${it.length()} bytes)" } ?: "missing from nativeLibraryDir"
            )
            val vms = VmManager.list(ctx)
            if (vms.isEmpty()) {
                out.appendLine("       disk image: no VM created yet")
            } else {
                vms.forEach { vm ->
                    val disk = VmProcess.diskPath(ctx, vm)
                    out.appendLine(
                        "       disk ${vm.name}: " +
                            if (disk.exists()) "${disk.length() / (1024 * 1024)} MB" else "absent (created on first start)"
                    )
                }
            }
            out.appendLine("       base image: ${VmImage.describe(ctx).lines().first()}")
        } catch (t: Throwable) {
            result(out, false, "engine artefacts", "${t.javaClass.simpleName}: ${t.message}")
        }

        // 10. required host binaries
        listOf("/system/bin/sh", "/system/bin/logcat").forEach { path ->
            val f = File(path)
            result(out, f.exists(), "binary $path", if (f.exists()) "present" else "missing")
        }

        out.appendLine()
        out.appendLine("finished in ${System.currentTimeMillis() - started} ms")
        val text = out.toString()
        LogStore.log(Channel.APP, "self-test finished:\n$text")
        return text
    }

    private fun check(name: String, ok: Boolean) {
        if (!ok) throw IllegalStateException("check returned false")
        LogStore.log(Channel.APP, "self-test PASS: $name")
    }

    private fun result(out: StringBuilder, ok: Boolean, name: String, detail: String) {
        val tag = if (ok) "[PASS]" else "[FAIL]"
        out.appendLine("$tag $name — $detail")
    }
}
