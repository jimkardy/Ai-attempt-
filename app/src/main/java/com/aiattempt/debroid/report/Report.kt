package com.aiattempt.debroid.report

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.aiattempt.debroid.log.LogStore
import com.aiattempt.debroid.sys.DeviceSupport
import com.aiattempt.debroid.sys.SysInfo
import com.aiattempt.debroid.vm.GuestExit
import com.aiattempt.debroid.vm.VmImage
import com.aiattempt.debroid.vm.VmManager
import com.aiattempt.debroid.vm.VmProcess
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds the copy-and-paste artifacts users send back: full report, log zip, issue link. */
object Report {

    private const val LOG_LIMIT = 90_000

    fun full(ctx: Context, selfTest: String? = null): String = buildString {
        appendLine("===== Debroid debug report =====")
        appendLine()
        appendLine(SysInfo.report(ctx))
        appendLine("--- Support ---")
        runCatching { appendLine(DeviceSupport.summary(ctx)) }
        if (!selfTest.isNullOrBlank()) {
            appendLine("--- Self-test ---")
            appendLine(selfTest)
        }
        appendLine("--- UML ---")
        runCatching {
            val vm = VmManager.running(ctx) ?: VmManager.list(ctx).firstOrNull()
            appendLine(VmProcess.describe(ctx, vm).trimEnd())
            appendLine(VmImage.describe(ctx))
            appendLine("log budget   : ${LogStore.totalBytes() / 1024} KB of ${LogStore.TOTAL_BUDGET_BYTES / (1024 * 1024)} MB")
            if (vm != null) GuestExit.explain(vm.lastExit ?: 0)?.let { appendLine("exit meaning : $it") }
        }
        appendLine("--- seccomp ---")
        runCatching {
            val vm = VmManager.running(ctx) ?: VmManager.list(ctx).firstOrNull()
            appendLine(GuestExit.report(vm?.lastExit).trimEnd())
        }
        appendLine()
        val consoles = runCatching { VmManager.consoleDump(ctx) }.getOrDefault("")
        if (consoles.isNotBlank()) {
            appendLine("--- VM consoles ---")
            appendLine(consoles)
        }
        appendLine("--- Logs ---")
        runCatching {
            appendLine(LogStore.fullText().take(LOG_LIMIT))
        }
    }

    fun copy(ctx: Context, text: String): Boolean = try {
        val cb = ctx.getSystemService(ClipboardManager::class.java)
        cb?.setPrimaryClip(ClipData.newPlainText("Debroid report", text))
        cb != null
    } catch (_: Throwable) {
        false
    }

    fun shareText(ctx: Context, text: String) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Debroid debug report")
                putExtra(Intent.EXTRA_TEXT, text)
            }
            ctx.startActivity(Intent.createChooser(intent, "Share Debroid report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {
        }
    }

    /** Writes a zip (report.txt + one .txt per log channel) into cacheDir/exports. */
    fun saveZip(ctx: Context, selfTest: String? = null): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { if (it.isFile && it.name.endsWith(".zip")) it.delete() }
        val zip = File(dir, "debroid-logs-$stamp.zip")
        val entries = LinkedHashMap<String, String>()
        entries["report.txt"] = full(ctx, selfTest)
        LogStore.logDir()?.listFiles()?.sortedBy { it.name }?.forEach { f ->
            if (f.isFile && f.length() < 4L * 1024 * 1024) {
                entries["logs/${f.name}"] = runCatching { f.readText() }.getOrDefault("")
            }
        }
        FileOutputStream(zip).use { fos ->
            ZipOutputStream(fos).use { zos ->
                entries.forEach { (name, content) ->
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(content.toByteArray())
                    zos.closeEntry()
                }
            }
        }
        return zip
    }

    fun shareFile(ctx: Context, file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Debroid logs (${file.name})")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(intent, "Share Debroid logs").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {
        }
    }


}
