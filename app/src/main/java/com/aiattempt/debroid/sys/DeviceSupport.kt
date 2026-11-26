package com.aiattempt.debroid.sys

import android.content.Context
import android.os.Build
import java.io.File

/**
 * What this device actually is, and whether Debroid can work on it.
 *
 * Debroid targets **arm64-v8a only, Android 10–15**, on kernels that can run
 * User-Mode Linux. This object answers that question in a way the user (and the
 * Debug report) can read, instead of failing somewhere deep in the boot.
 */
object DeviceSupport {

    data class Check(val name: String, val ok: Boolean, val detail: String)

    const val MIN_API = 29   // Android 10
    const val MAX_TESTED_API = 35 // Android 15

    fun isArm64(): Boolean = Build.SUPPORTED_ABIS.any { it.equals("arm64-v8a", ignoreCase = true) }

    fun is64BitRuntime(): Boolean = Build.SUPPORTED_ABIS.any { it.contains("64") }

    fun androidInRange(): Boolean = Build.VERSION.SDK_INT in MIN_API..MAX_TESTED_API

    fun androidNewerThanTested(): Boolean = Build.VERSION.SDK_INT > MAX_TESTED_API

    fun pageSizeOk(): Boolean = SysInfo.pageSizeKb() in listOf(4, 16)

    fun checks(ctx: Context): List<Check> = listOf(
        Check("cpu abi is arm64-v8a", isArm64(), Build.SUPPORTED_ABIS.joinToString(", ")),
        Check("64-bit userspace", is64BitRuntime(), System.getProperty("os.arch") ?: "?"),
        Check(
            "android ${MIN_API / 10}-${MAX_TESTED_API}",
            Build.VERSION.SDK_INT >= MIN_API,
            "API ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})" +
                if (androidNewerThanTested()) " — newer than tested, expect rough edges" else ""
        ),
        Check("page size 4K or 16K", pageSizeOk(), "${SysInfo.pageSizeKb()} KB"),
        Check("ram >= 2 GB", totalRamMb(ctx) >= 2048, "${totalRamMb(ctx)} MB"),
        Check("free storage >= 6 GB", SysInfo.freeStorageMb(ctx) >= 6 * 1024, "${SysInfo.freeStorageMb(ctx) / 1024} GB free"),
        Check("native lib dir executable", nativeDirExecutable(ctx), nativeDir(ctx).absolutePath)
    )

    fun totalRamMb(ctx: Context): Int = try {
        val am = ctx.getSystemService(android.app.ActivityManager::class.java)
        val info = android.app.ActivityManager.MemoryInfo()
        am?.getMemoryInfo(info)
        (info.totalMem / (1024 * 1024)).toInt()
    } catch (_: Throwable) {
        0
    }

    private fun nativeDir(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir)

    private fun nativeDirExecutable(ctx: Context): Boolean = try {
        val dir = nativeDir(ctx)
        dir.exists() && dir.canExecute()
    } catch (_: Throwable) {
        false
    }

    /** Best-effort UML readiness: says "likely" or names the blocker, never promises. */
    fun umlCapability(ctx: Context): String {
        val blockers = ArrayList<String>()
        if (!isArm64()) blockers.add("CPU is not arm64-v8a")
        if (!is64BitRuntime()) blockers.add("no 64-bit userspace")
        if (!pageSizeOk()) blockers.add("unsupported page size ${SysInfo.pageSizeKb()} KB")
        if (!nativeDirExecutable(ctx)) blockers.add("cannot execute binaries from the app's native dir")
        if (totalRamMb(ctx) < 2048) blockers.add("less than 2 GB RAM")
        if (blockers.isNotEmpty()) return "blocked: ${blockers.joinToString("; ")}"

        val kernel = System.getProperty("os.version") ?: "?"
        val mapCount = try {
            File("/proc/sys/vm/max_map_count").readText().trim()
        } catch (_: Throwable) {
            "?"
        }
        val selinux = try {
            when (File("/sys/fs/selinux/enforce").readText().trim()) {
                "1" -> "enforcing"
                "0" -> "permissive"
                else -> "unknown"
            }
        } catch (_: Throwable) {
            "unknown"
        }
        return "likely (arm64, ${SysInfo.pageSizeKb()} KB pages, kernel $kernel, max_map_count $mapCount, selinux $selinux)"
    }

    fun summary(ctx: Context): String = buildString {
        appendLine("device support : " + if (isArm64()) "arm64-v8a" else "UNSUPPORTED (${SysInfo.abi()})")
        appendLine("android        : API ${Build.VERSION.SDK_INT} " +
            if (androidNewerThanTested()) "(newer than tested 10-15)" else "(supported range 10-15)")
        appendLine("uml capability : ${umlCapability(ctx)}")
        checks(ctx).forEach { c ->
            appendLine(if (c.ok) "[PASS] ${c.name} — ${c.detail}" else "[FAIL] ${c.name} — ${c.detail}")
        }
    }
}
