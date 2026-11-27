package com.aiattempt.debroid.vm

import android.content.Context
import android.system.Os
import com.aiattempt.debroid.BuildConfig
import com.aiattempt.debroid.log.Channel
import com.aiattempt.debroid.log.LogStore
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * The Debian disk image every VM is cloned from, and the per-VM copy of it.
 *
 * The image is a real ext4 filesystem built by `.github/workflows/debug-build.yml`
 * from Debian trixie arm64 (see `native/rootfs/`). It is published next to the APK
 * on the same `debug-v*` release, so the APK and the image it boots always come from
 * the same commit. It is far too large to put inside an APK, so it is fetched once,
 * on the first Start, and kept in `files/images/` for every VM after that.
 *
 * Everything is checked before it is used: a truncated download, a gzip that does
 * not decompress or a file without an ext4 superblock is rejected and deleted
 * instead of being handed to the kernel.
 */
object VmImage {

    /** Asset name published with every `debug-v*` release. */
    const val ASSET = "debroid-rootfs-trixie-arm64.ext4.gz"

    /** Size of the ext4 filesystem the workflow builds (2 GiB, mostly holes). */
    const val BASE_BYTES = 2L * 1024 * 1024 * 1024

    /** Below this the "base image" is garbage, not a Debian root. */
    private const val MIN_BASE_BYTES = 200L * 1024 * 1024

    private const val CHUNK = 1024 * 1024
    private const val EXT4_MAGIC_OFFSET = 1080L

    fun dir(ctx: Context): File = File(ctx.filesDir, "images").apply { mkdirs() }

    fun baseImage(ctx: Context): File = File(dir(ctx), "base-rootfs.ext4")

    fun url(): String =
        "https://github.com/jimkardy/Ai-attempt-/releases/download/" +
            "debug-v${BuildConfig.VERSION_NAME}/$ASSET"

    fun isReady(ctx: Context): Boolean {
        val f = baseImage(ctx)
        return f.exists() && f.length() >= MIN_BASE_BYTES && looksLikeExt4(f)
    }

    /**
     * True when this VM already has a disk with a real ext4 filesystem in it.
     * VMs created by 0.2.x have an empty sparse `disk.img` (nothing was ever
     * booted into it), which this correctly reports as not bootable so it gets
     * rebuilt instead of being handed to the kernel as a corrupt root.
     */
    fun hasBootableDisk(ctx: Context, vm: VmInstance): Boolean {
        val f = VmStore.diskImage(ctx, vm.id)
        return f.exists() && f.length() >= MIN_BASE_BYTES && looksLikeExt4(f)
    }

    /**
     * Makes sure [vm] has a disk image, fetching and unpacking the base image if
     * this is the first VM. Never overwrites an existing disk: a booted guest's
     * filesystem is not something to throw away because a form was reopened.
     */
    fun ensureDiskImage(ctx: Context, vm: VmInstance, log: (String) -> Unit): Result<File> {
        val disk = VmStore.diskImage(ctx, vm.id)
        val wanted = maxOf(vm.diskMb.toLong() * 1024L * 1024L, BASE_BYTES)
        if (disk.exists() && disk.length() > 0 && !looksLikeExt4(disk)) {
            // An empty image from an older build, or a half-written one from a kill
            // in the middle of a copy. Keep it aside rather than deleting anything.
            val aside = File(disk.parentFile, "disk.img.unusable")
            runCatching { aside.delete() }
            val moved = runCatching { disk.renameTo(aside) }.getOrDefault(false)
            log(
                if (moved) "the old disk image had no filesystem in it; moved to ${aside.name} and rebuilding"
                else "the existing disk image is not usable and could not be moved aside"
            )
            if (!moved) runCatching { disk.delete() }
        }
        if (disk.exists() && disk.length() > 0) {
            if (disk.length() < wanted) {
                val ok = runCatching { RandomAccessFile(disk, "rw").use { it.setLength(wanted) } }.isSuccess
                log(
                    if (ok) "disk image extended to ${vm.diskMb} MB — the guest grows the filesystem at boot"
                    else "could not extend the disk image to ${vm.diskMb} MB"
                )
            } else {
                log("disk image ready: ${disk.length() / (1024 * 1024)} MB")
            }
            return Result.success(disk)
        }

        val base = ensureBaseImage(ctx, log).getOrElse { return Result.failure(it) }
        val tmp = File(VmStore.vmDir(ctx, vm.id), "disk.img.part")
        return try {
            tmp.delete()
            log("creating this VM's disk: ${vm.diskMb} MB sparse, from the base image")
            copySparse(base, tmp, log)
            RandomAccessFile(tmp, "rw").use { raf ->
                if (raf.length() < wanted) raf.setLength(wanted)
            }
            if (!tmp.renameTo(disk)) {
                tmp.delete()
                return Result.failure(IOException("could not move the new disk image into place"))
            }
            log("disk image ready: ${disk.length() / (1024 * 1024)} MB")
            Result.success(disk)
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            LogStore.logStack(Channel.ENGINE, "could not create the disk image for ${vm.name}", t)
            Result.failure(t)
        }
    }

    /** Downloads and unpacks the shared base image once. */
    fun ensureBaseImage(ctx: Context, log: (String) -> Unit): Result<File> {
        val target = baseImage(ctx)
        if (isReady(ctx)) {
            log("base image already on the device: ${target.length() / (1024 * 1024)} MB")
            return Result.success(target)
        }
        runCatching { target.delete() }
        val part = File(dir(ctx), "base-rootfs.ext4.part")
        val source = url()
        log("fetching the Debian trixie arm64 image (one time): $source")
        LogStore.log(Channel.ENGINE, "image download start: $source")
        return try {
            part.delete()
            val conn = (URL(source).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 45_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Debroid/${BuildConfig.VERSION_NAME}")
            }
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return Result.failure(IOException("the image server answered HTTP $code"))
            }
            val compressed = conn.contentLengthLong
            log(
                if (compressed > 0) "download size: ${compressed / (1024 * 1024)} MB"
                else "download size: unknown"
            )
            var written = 0L
            var shown = -1
            var lastLog = 0L
            GZIPInputStream(conn.inputStream.buffered(128 * 1024), 128 * 1024).use { gz ->
                RandomAccessFile(part, "rw").use { out ->
                    out.setLength(0)
                    out.setLength(BASE_BYTES)
                    out.seek(0)
                    val buf = ByteArray(CHUNK)
                    while (true) {
                        val n = gz.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        if (isZero(buf, n)) {
                            out.seek(written + n)
                        } else {
                            out.write(buf, 0, n)
                        }
                        written += n
                        val pct = ((written * 100) / BASE_BYTES).toInt().coerceIn(0, 100)
                        if (pct >= shown + 10 || System.currentTimeMillis() - lastLog > 5000) {
                            shown = pct
                            lastLog = System.currentTimeMillis()
                            log("unpacking the image: $pct%")
                        }
                    }
                }
            }
            conn.disconnect()
            if (part.length() < MIN_BASE_BYTES || !looksLikeExt4(part)) {
                runCatching { part.delete() }
                return Result.failure(IOException("the downloaded image is not a Debian ext4 filesystem"))
            }
            runCatching { target.delete() }
            if (!part.renameTo(target)) {
                runCatching { part.delete() }
                return Result.failure(IOException("could not store the image in ${dir(ctx).absolutePath}"))
            }
            log("image ready: ${target.length() / (1024 * 1024)} MB in ${dir(ctx).absolutePath}")
            LogStore.log(Channel.ENGINE, "image download complete: ${target.length()} bytes")
            Result.success(target)
        } catch (t: Throwable) {
            runCatching { part.delete() }
            LogStore.logStack(Channel.ENGINE, "image download failed", t)
            Result.failure(t)
        }
    }

    /** Human-readable state for the Debug report. */
    fun describe(ctx: Context): String {
        val base = baseImage(ctx)
        val disk = if (base.exists()) "${base.length() / (1024 * 1024)} MB" else "missing"
        return "base image   : $disk (${base.absolutePath})\n" +
            "image source : ${url()}"
    }

    // ---- helpers ----------------------------------------------------------

    /** Copies [from] to [to], skipping all-zero chunks so the copy stays sparse. */
    private fun copySparse(from: File, to: File, log: (String) -> Unit) {
        val total = from.length()
        RandomAccessFile(from, "r").use { input ->
            RandomAccessFile(to, "rw").use { output ->
                output.setLength(total)
                output.seek(0)
                val buf = ByteArray(CHUNK)
                var pos = 0L
                var shown = -1
                while (pos < total) {
                    input.seek(pos)
                    val n = input.read(buf, 0, minOf(CHUNK.toLong(), total - pos).toInt())
                    if (n <= 0) break
                    if (!isZero(buf, n)) {
                        output.seek(pos)
                        output.write(buf, 0, n)
                    }
                    pos += n
                    val pct = ((pos * 100) / total).toInt()
                    if (pct >= shown + 25) {
                        shown = pct
                        log("copying the base image: $pct%")
                    }
                }
            }
        }
    }

    private fun isZero(buf: ByteArray, n: Int): Boolean {
        var i = 0
        while (i < n) {
            if (buf[i].toInt() != 0) return false
            i++
        }
        return true
    }

    private fun looksLikeExt4(f: File): Boolean = try {
        RandomAccessFile(f, "r").use { raf ->
            raf.seek(EXT4_MAGIC_OFFSET)
            // 0xEF53 little endian is the ext2/3/4 superblock magic.
            raf.read() == 0x53 && raf.read() == 0xEF
        }
    } catch (_: Throwable) {
        false
    }

    fun percent(bytes: Long, total: Long): String =
        String.format(Locale.US, "%d%%", if (total <= 0) 0 else (bytes * 100 / total).toInt())

    /**
     * Bytes this image really occupies on Android's filesystem. The file is
     * sparse, so its length is the *reservation* while this is the bill: it only
     * shrinks when the guest's own `fstrim` discards blocks (UML's ubd driver
     * turns a discard into a hole punch on this very file).
     */
    fun allocatedBytes(f: File): Long = try {
        Os.lstat(f.absolutePath).st_blocks * 512L
    } catch (_: Throwable) {
        runCatching { f.length() }.getOrDefault(0L)
    }

    /**
     * Size of the ext4 filesystem *inside* the image, read from the superblock
     * (s_blocks_count_* and s_log_block_size). Null when there is no filesystem.
     * This is what `df /` shows in the guest; it is normally smaller than the
     * disk file, because the guest only grows it at boot.
     */
    fun fsBytes(f: File): Long? = try {
        RandomAccessFile(f, "r").use { raf ->
            if (raf.length() < 2048) return null
            val sb = ByteArray(1024)
            raf.seek(1024)
            if (raf.read(sb) != sb.size) return null
            // 0xEF53 little endian at offset 56 of the superblock.
            if (sb[56] != 0x53.toByte() || sb[57] != 0xEF.toByte()) return null
            val blocksLo = le32(sb, 4)
            val logBlockSize = le32(sb, 24)
            if (logBlockSize > 6) return null
            val blockSize = 1024L shl logBlockSize.toInt()
            blocksLo * blockSize
        }
    } catch (_: Throwable) {
        null
    }

    private fun le32(buf: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 3 downTo 0) {
            v = (v shl 8) or (buf[offset + i].toLong() and 0xFFL)
        }
        return v
    }
}
