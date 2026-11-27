package com.aiattempt.debroid.sys

import android.content.Context
import android.os.StatFs
import com.aiattempt.debroid.log.Channel
import com.aiattempt.debroid.log.LogStore
import com.aiattempt.debroid.vm.VmImage
import com.aiattempt.debroid.vm.VmInstance
import com.aiattempt.debroid.vm.VmManager
import com.aiattempt.debroid.vm.VmStore
import java.io.File
import java.util.Locale

/**
 * "Reclaim storage" — done where it actually matters: inside the VM's disk.
 *
 * This used to run `fstrim -v /data` on Android, which was the wrong filesystem:
 * deleting a 1 GB file inside the guest leaves those blocks marked used in the
 * VM's `disk.img`, and trimming Android's /data cannot free them. The trim now
 * runs inside the guest (`fstrim -v /` over its console); UML's `ubd` driver
 * enables discard by default and turns each discard into a hole punch on
 * `disk.img`, so the space really does come back to Android. The numbers shown
 * are Android's own allocation of that file (`st_blocks`), before and after.
 *
 * The caches Debroid owns are still cleared here, since they live on Android.
 */
object StorageReclaim {

    fun run(ctx: Context, vm: VmInstance): String {
        val disk = VmStore.diskImage(ctx, vm.id)
        val before = VmImage.allocatedBytes(disk)
        val freeBefore = freeBytes(ctx)
        val fsBytes = VmImage.fsBytes(disk)
        val text = buildString {
            appendLine("Reclaim storage — ${LogStore.stamp()}")
            appendLine("VM            : ${vm.name} (${vm.state})")
            appendLine("disk reserved : ${VmInstance.formatDisk(vm.diskMb)} (sparse file)")
            appendLine("filesystem    : ${fsBytes?.let { mb(it) + " MB" } ?: "unknown"} inside the disk")
            appendLine("used on Android: ${mb(before)} MB")
            appendLine()

            appendLine("[1] trim inside the guest — fstrim -v /")
            val trim = VmManager.trimGuestDisk(ctx, vm.id)
            appendLine(trim.output.ifBlank { "(no output)" })
            appendLine()

            appendLine("[2] Debroid caches (these live on Android)")
            append(purge(ctx))
            val freeAfter = freeBytes(ctx)
            appendLine()
            appendLine("android free space: ${mb(freeBefore)} MB -> ${mb(freeAfter)} MB")
            appendLine(
                "this VM's disk  : ${mb(trim.hostBefore)} MB -> ${mb(trim.hostAfter)} MB " +
                    "(${mb(trim.hostFreed)} MB released)"
            )
            appendLine()
            append(
                if (trim.ran) {
                    "Trim is done. Space freed inside the guest no longer costs Android anything: the " +
                        "blocks were punched out of the disk image itself."
                } else {
                    "Start the VM and run this again: the trim has to happen inside the guest's own " +
                        "filesystem, so it cannot run while the VM is stopped."
                }
            )
        }
        LogStore.log(Channel.APP, "reclaim storage:\n$text")
        return text
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
