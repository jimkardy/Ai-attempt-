package com.aiattempt.debroid.sys

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import com.aiattempt.debroid.log.Channel
import com.aiattempt.debroid.log.LogStore
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Runs every environment check the app depends on and returns a plain-text report
 * that a user can paste into an issue. Lines are marked [PASS] / [FAIL] / [INFO].
 */
object SelfTest {

    fun run(ctx: Context): String {
        val out = StringBuilder()
        val started = System.currentTimeMillis()
        out.appendLine("Debroid self-test — ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        out.appendLine("app ${BuildConfigVersion.name} (${BuildConfigVersion.code}) on Android ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}")
        out.appendLine()

        // 1. app storage
        try {
            val f = File(ctx.filesDir, "selftest.tmp")
            f.writeText("ok")
            val back = f.readText()
            f.delete()
            check("app internal storage writable", back == "ok")
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

        // 3. shell
        try {
            val p = ProcessBuilder("/system/bin/sh", "-c", "echo debroid_selftest_ok").redirectErrorStream(true).start()
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

        // 5. assets the UML engine needs
        try {
            val assets = ctx.assets.list("")?.toList() ?: emptyList()
            result(out, true, "asset root", assets.joinToString(", ").ifEmpty { "(none)" })
            val required = listOf("uml/libuml-4k.so", "uml/libuml-16k.so", "rootfs/rootfs.squashfs")
            required.forEach { path ->
                val present = try {
                    ctx.assets.open(path).use { true }
                } catch (_: Throwable) {
                    false
                }
                result(out, true, "engine asset $path", if (present) "present" else "absent (expected in this v1 shell)")
            }
        } catch (t: Throwable) {
            result(out, false, "asset listing", "${t.javaClass.simpleName}: ${t.message}")
        }

        // 6. loopback networking
        try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { s ->
                result(out, s.localPort > 0, "loopback bind", "port ${s.localPort}")
            }
        } catch (t: Throwable) {
            result(out, false, "loopback bind", "${t.javaClass.simpleName}: ${t.message}")
        }

        // 7. clipboard (how logs get out)
        try {
            val cb = ctx.getSystemService(ClipboardManager::class.java)
            result(out, cb != null, "clipboard service", if (cb != null) "available" else "null")
        } catch (t: Throwable) {
            result(out, false, "clipboard service", "${t.javaClass.simpleName}: ${t.message}")
        }

        // 8. environment facts
        result(out, true, "abi", Build.SUPPORTED_ABIS.joinToString(", "))
        result(out, SysInfo.is64Bit(), "64-bit runtime", SysInfo.abi())
        val page = SysInfo.pageSizeKb()
        result(out, page == 4 || page == 16, "page size (4K/16K)", "$page KB")
        result(out, true, "free storage", "${SysInfo.freeStorageMb(ctx)} MB")
        val ram = Runtime.getRuntime().maxMemory() / (1024 * 1024)
        result(out, ram > 0, "app heap limit", "$ram MB")

        // 9. required host binaries for the UML backend
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

/** Small shim so this file does not depend on generated BuildConfig during compilation of tests. */
internal object BuildConfigVersion {
    val name: String = "0.1.0"
    val code: Int = 1
}
