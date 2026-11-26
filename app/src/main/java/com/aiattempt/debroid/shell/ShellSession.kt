package com.aiattempt.debroid.shell

import java.io.File
import java.io.OutputStream

/**
 * Minimal line-oriented host shell (`/system/bin/sh -i`) running as the app uid.
 * No pty, so no job control. It now starts in a directory the app can actually
 * read and with a sane prompt, instead of `/` ("ls: .: Permission denied").
 */
class ShellSession(
    private val workDir: File? = null,
    private val onLine: (String) -> Unit
) {

    @Volatile
    var running: Boolean = false
        private set

    private var process: Process? = null
    private var input: OutputStream? = null
    private var readerThread: Thread? = null

    private val ansi = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

    fun start(): Boolean {
        if (running) return true
        return try {
            val dir = (workDir ?: File("/data/local/tmp")).let { d ->
                if (!d.exists()) d.mkdirs()
                d.takeIf { it.isDirectory } ?: File("/")
            }
            val builder = ProcessBuilder("/system/bin/sh", "-i")
                .redirectErrorStream(true)
                .directory(dir)
            builder.environment().apply {
                put("HOME", dir.absolutePath)
                put("PWD", dir.absolutePath)
                put("PATH", "/sbin:/system/sbin:/system/bin:/system/xbin:/vendor/bin")
                put("PS1", "debroid:\\w\\$ ")
                put("ENV", "")
            }
            val p = builder.start()
            process = p
            input = p.outputStream
            running = true
            readerThread = Thread({
                try {
                    p.inputStream.bufferedReader().use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            onLine(clean(line))
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    running = false
                }
            }, "shell-reader").apply {
                isDaemon = true
                start()
            }
            onLine("host shell started (uid ${android.os.Process.myUid()}) in ${dir.absolutePath}")
            true
        } catch (t: Throwable) {
            onLine("failed to start shell: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    fun exec(command: String) {
        val out = input
        if (out == null || !running) {
            onLine("shell is not running")
            return
        }
        try {
            out.write((command + "\n").toByteArray())
            out.flush()
        } catch (t: Throwable) {
            onLine("write failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    fun stop() {
        running = false
        try {
            input?.close()
        } catch (_: Throwable) {
        }
        try {
            process?.destroy()
        } catch (_: Throwable) {
        }
        process = null
        input = null
    }

    private fun clean(line: String): String = line.replace(ansi, "").replace("\r", "")
}
