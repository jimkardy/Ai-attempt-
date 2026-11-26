package com.aiattempt.debroid.sys

import android.content.Context
import android.os.StatFs
import com.aiattempt.debroid.log.Channel
import com.aiattempt.debroid.log.LogStore
import com.aiattempt.debroid.vm.VmImage
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * "Reclaim Android storage": asks the kernel to discard the blocks of deleted
 * files (`fstrim`), then purges the caches Debroid itself owns.
 *
 * fstrim needs CAP_SYS_ADMIN, which a normal app does not have, so on a
 * non-rooted phone the call itself is refused by the kernel. That is reported
 * honestly instead of being hidden: the report shows the exact command, its
 * exit code and its output, plus how much free space actually changed. When a
 * root shell is available (`su`) the same command is run through it and really
 * does trim the filesystem.
 */
object StorageReclaim {

    private const val TIMEOUT_SECONDS = 25L

    /** Files bigger than this are never trimmed by path, only by the whole fs. */
    private data class Exec(val code: Int, val output: String)

    fun run(ctx: Context): String = buildString {
        appendLine("Reclaim Android storage — ${LogStore.stamp()}")
        val before = freeBytes(ctx)
        appendLine("free space before : ${mb(before)} MB")
        appendLine()

        appendLine("[1] fstrim -v /  (the kernel's discard of deleted blocks)")
        val trim = fstrim()
        appendLine(trim.output.trim().ifBlank { "(no output)" })
        appendLine("result: ${trim.summary}")
        appendLine()

        appendLine("[2] Debroid caches")
        append(purge(ctx))
        val after = freeBytes(ctx)
        appendLine()
        appendLine("free space after  : ${mb(after)} MB")
        appendLine("difference        : ${mb(after - before)} MB")
        appendLine()
        append(
            if (trim.trimmed) {
                "The filesystem was trimmed: the blocks of already-deleted files are now free for reuse."
            } else {
                "fstrim was refused (exit ${trim.code}). Android only lets a root process discard blocks, " +
                    "and this build runs as a normal app. Nothing was damaged; the space a trimmed " +
                    "filesystem would return stays reserved until Android's own periodic trim runs " +
                    "(Android does this by itself, usually while charging and idle)."
            }
        )
    }.also { text ->
        LogStore.log(Channel.APP, "reclaim storage:\n$text")
    }

    // ---- fstrim -----------------------------------------------------------

    private class Trim(val trimmed: Boolean, val code: Int, val output: String) {
        val summary: String
            get() = if (trimmed) "trimmed" else "not permitted (no root)"
    }

    private fun fstrim(): Trim {
        // 1. root shell, if the phone has one. This is the only path that really trims.
        execWithTimeout(listOf("su", "-c", "/system/bin/fstrim -v /data; /system/bin/fstrim -v /"))?.let { r ->
            if (r.code == 0 && r.output.contains("trimmed", ignoreCase = true)) {
                return Trim(true, 0, r.output)
            }
        }
        // 2. the trim binary directly, as the app. The kernel will refuse without CAP_SYS_ADMIN;
        //    the point of running it is to report exactly what Android said.
        val direct = listOf(
            listOf("/system/bin/fstrim", "-v", "/data"),
            listOf("/system/xbin/fstrim", "-v", "/data"),
            listOf("fstrim", "-v", "/data")
        )
        var last: Trim? = null
        for (cmd in direct) {
            val r = execWithTimeout(cmd) ?: continue
            val trimmed = r.code == 0 && r.output.contains("trimmed", ignoreCase = true)
            val t = Trim(trimmed, r.code, "$ ${cmd.joinToString(" ")}\nexit ${r.code}\n${r.output.trim()}")
            if (trimmed) return t
            last = t
        }
        return last ?: Trim(false, -1, "$ fstrim -v /data\nexit -1\nno fstrim binary found on this device")
    }

    private fun execWithTimeout(command: List<String>): Exec? {
        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (_: Throwable) {
            return null
        }
        val out = StringBuilder()
        val reader = Thread({
            try {
                process.inputStream.bufferedReader().use { r ->
                    r.lineSequence().forEach { line ->
                        if (out.length < 4000) out.appendLine(line)
                    }
                }
            } catch (_: Throwable) {
            }
        }, "reclaim-read").apply { isDaemon = true; start() }
        val finished = runCatching {
            process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }.getOrDefault(false)
        if (!finished) {
            runCatching { process.destroyForcibly() }
        }
        runCatching { reader.join(1000) }
        val code = runCatching { process.exitValue() }.getOrDefault(-1)
        return Exec(if (finished) code else -1, out.toString())
    }

    // ---- Debroid's own caches --------------------------------------------

    private fun purge(ctx: Context): String = buildString {
        appendLine(purgeDir(File(ctx.cacheDir, "exports"), "exported log zips"))
        appendLine(purgeDir(File(ctx.cacheDir, "logs"), "cached logs"))
        appendLine(purgeLeftoverParts(ctx))
    }

    private fun purgeDir(dir: File, label: String): String {
        val files = runCatching { dir.listFiles()?.filter { it.isFile } }.getOrNull().orEmpty()
        var freed = 0L
        var count = 0
        files.forEach { f ->
            val len = f.length()
            if (runCatching { f.delete() }.getOrDefault(false)) {
                freed += len
                count++
            }
        }
        return "  $label: removed $count file(s), ${mb(freed)} MB"
    }

    private fun purgeLeftoverParts(ctx: Context): String {
        val dir = VmImage.dir(ctx)
        val leftovers = runCatching {
            dir.listFiles()?.filter { it.isFile && (it.name.endsWith(".part") || it.name.endsWith(".tmp")) }
        }.getOrNull().orEmpty()
        var freed = 0L
        leftovers.forEach { f ->
            val len = f.length()
            if (runCatching { f.delete() }.getOrDefault(false)) freed += len
        }
        return "  interrupted downloads: removed ${leftovers.size} file(s), ${mb(freed)} MB"
    }

    // ---- numbers ----------------------------------------------------------

    private fun freeBytes(ctx: Context): Long = try {
        StatFs(ctx.filesDir.absolutePath).availableBytes
    } catch (_: Throwable) {
        0L
    }

    private fun mb(bytes: Long): String = String.format(Locale.US, "%.0f", bytes / (1024.0 * 1024.0))
}
