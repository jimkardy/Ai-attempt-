package com.aiattempt.debroid.log

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Log channels shown in the Debug tab. Every channel is kept in memory (bounded)
 * and mirrored to a rotating file, so logs survive a crash and a process kill.
 */
enum class Channel(val label: String) {
    APP("App"),
    ENGINE("Engine"),
    CONSOLE("Console"),
    SHELL("Shell"),
    LOGCAT("Logcat"),
    CRASH("Crash")
}

object LogStore {
    private const val MAX_LINES = 4000
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024
    private const val TAG = "Debroid"

    private val buffers = HashMap<Channel, ArrayDeque<String>>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileLock = Any()

    @Volatile
    private var dir: File? = null

    @Volatile
    private var crashFile: File? = null

    @Volatile
    private var lastNotify = 0L

    fun init(ctx: Context) {
        if (dir != null) return
        val d = File(ctx.applicationContext.filesDir, "logs")
        try {
            d.mkdirs()
        } catch (_: Throwable) {
        }
        dir = d
        crashFile = File(d, "pending-crash.txt")
        Channel.entries.forEach { buffers[it] = ArrayDeque() }
    }

    /** Directory holding the log files (may be null if init never ran). */
    fun logDir(): File? = dir

    /** Timestamp prefix used by log lines and VM consoles. */
    fun stamp(): String = synchronized(fmt) { fmt.format(Date()) }

    fun log(channel: Channel, message: String) {
        val line = "${fmt.format(Date())}  $message"
        synchronized(buffers) {
            val buf = buffers.getOrPut(channel) { ArrayDeque() }
            buf.addLast(line)
            while (buf.size > MAX_LINES) buf.removeFirst()
        }
        if (channel != Channel.LOGCAT) {
            try {
                Log.println(Log.INFO, TAG, "[${channel.label}] $message")
            } catch (_: Throwable) {
            }
        }
        append(channel, line)
        notifyThrottled()
    }

    fun logStack(channel: Channel, message: String, t: Throwable) {
        log(channel, "$message\n${stackOf(t)}")
    }

    fun stackOf(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    fun lines(channel: Channel): List<String> = synchronized(buffers) {
        buffers[channel]?.toList() ?: emptyList()
    }

    fun tail(channel: Channel, count: Int): String {
        val all = lines(channel)
        return all.takeLast(count).joinToString("\n")
    }

    fun fullText(): String = buildString {
        Channel.entries.forEach { c ->
            val ls = lines(c)
            if (ls.isNotEmpty()) {
                append("===== ${c.label} — ${ls.size} lines =====\n")
                append(ls.joinToString("\n"))
                append("\n\n")
            }
        }
    }

    fun clearAll() {
        synchronized(buffers) { buffers.values.forEach { it.clear() } }
        try {
            dir?.listFiles()?.forEach { it.delete() }
        } catch (_: Throwable) {
        }
    }

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    // ---- crash persistence -------------------------------------------------

    fun recordCrash(thread: Thread, e: Throwable) {
        val text = "when=${fmt.format(Date())}\nthread=${thread.name}\n\n${stackOf(e)}"
        try {
            crashFile?.writeText(text)
        } catch (_: Throwable) {
        }
    }

    fun pendingCrash(): String? = try {
        crashFile?.takeIf { it.exists() && it.length() > 0 }?.readText()
    } catch (_: Throwable) {
        null
    }

    fun acknowledgeCrash() {
        try {
            crashFile?.delete()
        } catch (_: Throwable) {
        }
    }

    private fun append(channel: Channel, line: String) {
        val d = dir ?: return
        synchronized(fileLock) {
            try {
                val f = File(d, "log-${channel.name.lowercase(Locale.US)}.txt")
                if (f.length() > MAX_FILE_BYTES) {
                    val old = File(d, f.name + ".1")
                    old.delete()
                    f.renameTo(old)
                }
                f.appendText(line + "\n")
            } catch (_: Throwable) {
            }
        }
    }

    private fun notifyThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastNotify < 400) return
        lastNotify = now
        listeners.forEach {
            try {
                it()
            } catch (_: Throwable) {
            }
        }
    }
}
