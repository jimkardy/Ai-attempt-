package com.aiattempt.debroid.vm

import android.content.Context
import com.aiattempt.debroid.log.Channel
import com.aiattempt.debroid.log.LogStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * JSON persistence for the VM list. Small, dependency-free and crash-safe:
 * writes go to a temp file first, then rename over the old one, so a kill in the
 * middle can never leave a truncated `vms.json`.
 */
object VmStore {

    private const val FILE = "vms.json"

    fun root(ctx: Context): File = File(ctx.filesDir, "vms").apply { mkdirs() }

    fun vmDir(ctx: Context, id: String): File = File(root(ctx), id).apply { mkdirs() }

    fun diskImage(ctx: Context, id: String): File = File(vmDir(ctx, id), "disk.img")

    fun consoleFile(ctx: Context, id: String): File = File(vmDir(ctx, id), "console.log")

    fun load(ctx: Context): List<VmInstance> = try {
        val f = File(root(ctx), FILE)
        if (!f.exists() || f.length() == 0L) {
            emptyList()
        } else {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { VmInstance.fromJson(it) } }
        }
    } catch (t: Throwable) {
        LogStore.logStack(Channel.APP, "could not read vms.json, starting with an empty list", t)
        emptyList()
    }

    fun save(ctx: Context, vms: List<VmInstance>): Boolean = try {
        val arr = JSONArray()
        vms.forEach { arr.put(it.toJson()) }
        val dir = root(ctx)
        val tmp = File(dir, "$FILE.tmp")
        tmp.writeText(arr.toString())
        val target = File(dir, FILE)
        if (target.exists()) target.delete()
        val ok = tmp.renameTo(target)
        if (!ok) {
            target.writeText(arr.toString())
            tmp.delete()
        }
        true
    } catch (t: Throwable) {
        LogStore.logStack(Channel.APP, "could not write vms.json", t)
        false
    }

    /** Sparse image: the file claims [sizeMb] but only written blocks cost storage. */
    fun allocateDisk(ctx: Context, id: String, sizeMb: Int): Boolean = try {
        val f = diskImage(ctx, id)
        java.io.RandomAccessFile(f, "rw").use { it.setLength(sizeMb.toLong() * 1024L * 1024L) }
        true
    } catch (t: Throwable) {
        LogStore.logStack(Channel.APP, "could not allocate disk image for $id", t)
        false
    }

    fun deleteVm(ctx: Context, id: String) {
        try {
            vmDir(ctx, id).deleteRecursively()
        } catch (t: Throwable) {
            LogStore.logStack(Channel.APP, "could not delete VM directory $id", t)
        }
    }
}
