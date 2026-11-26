package com.aiattempt.debroid.shell

import java.io.OutputStream

/**
 * Minimal line-oriented interactive shell (`/system/bin/sh -i`) running as the app
 * uid. No pty, so no job control — good enough to inspect the device while the
 * real UML console lands. Output is ANSI-stripped before it reaches the UI.
 */
class ShellSession(private val onLine: (String) -> Unit) {

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
            val p = ProcessBuilder("/system/bin/sh", "-i")
                .redirectErrorStream(true)
                .start()
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
            onLine("shell started (uid ${android.os.Process.myUid()})")
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
