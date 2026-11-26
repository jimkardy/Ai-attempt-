package com.aiattempt.debroid.sys

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import com.aiattempt.debroid.DebroidApp
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SysInfo {

    fun pageSizeKb(): Int = try {
        (Os.sysconf(OsConstants._SC_PAGESIZE) / 1024L).toInt()
    } catch (_: Throwable) {
        0
    }

    fun abi(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

    fun is64Bit(): Boolean = Build.SUPPORTED_ABIS.any { it.contains("64") }

    fun freeStorageMb(ctx: Context): Long = try {
        val st = StatFs(ctx.filesDir.absolutePath)
        st.availableBytes / (1024 * 1024)
    } catch (_: Throwable) {
        -1
    }

    fun signatureSha256(ctx: Context): String = try {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, flags)
        val certs = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        val bytes = certs?.firstOrNull()?.toByteArray()
        if (bytes == null) "none" else MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(":") { "%02X".format(it) }
    } catch (t: Throwable) {
        "unavailable (${t.javaClass.simpleName})"
    }

    fun report(ctx: Context): String {
        val app = ctx.applicationContext as? DebroidApp
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val pm = ctx.packageManager
        val pkg = try {
            pm.getPackageInfo(ctx.packageName, 0)
        } catch (_: Throwable) {
            null
        }
        val logsDir = File(ctx.filesDir, "logs")
        return buildString {
            appendLine("--- App ---")
            appendLine("package      : ${ctx.packageName}")
            appendLine("version      : ${pkg?.versionName ?: "?"} (versionCode ${pkg?.versionCode ?: -1})")
            appendLine("debuggable   : ${(ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0}")
            appendLine("signed sha256: ${signatureSha256(ctx)}")
            appendLine("pid          : ${android.os.Process.myPid()}")
            appendLine("uptime app   : ${if (app != null) "running" else "?"}")
            appendLine("logs dir     : ${logsDir.absolutePath}")
            appendLine("logs size    : ${dirSize(logsDir) / 1024} KB")
            appendLine()
            appendLine("--- Device ---")
            appendLine("manufacturer : ${Build.MANUFACTURER}")
            appendLine("model        : ${Build.MODEL}")
            appendLine("device       : ${Build.DEVICE} (${Build.BOARD})")
            appendLine("android      : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("build id     : ${Build.DISPLAY}")
            appendLine("abi          : ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine("64-bit       : ${is64Bit()}")
            appendLine("page size    : ${pageSizeKb()} KB")
            appendLine("kernel       : ${System.getProperty("os.version")} / ${System.getProperty("os.arch")}")
            appendLine("selinux      : ${selinuxMode()}")
            appendLine()
            appendLine("--- Resources ---")
            appendLine("ram total    : ${if (mem != null) mem.totalMem / (1024 * 1024) else -1} MB")
            appendLine("ram avail    : ${if (mem != null) mem.availMem / (1024 * 1024) else -1} MB")
            appendLine("low memory   : ${mem?.lowMemory ?: "?"}")
            appendLine("heap max     : ${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MB")
            appendLine("storage free : ${freeStorageMb(ctx)} MB")
            appendLine("locale       : ${Locale.getDefault()}")
            appendLine("timezone     : ${SimpleDateFormat("Z", Locale.US).format(Date())}")
            appendLine("time         : ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        }
    }

    private fun dirSize(dir: File): Long = try {
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    } catch (_: Throwable) {
        0
    }

    private fun selinuxMode(): String = try {
        val line = java.io.File("/sys/fs/selinux/enforce").takeIf { it.exists() }?.readText()?.trim()
        when (line) {
            "1" -> "enforcing"
            "0" -> "permissive"
            else -> "unknown"
        }
    } catch (_: Throwable) {
        "unknown"
    }
}
