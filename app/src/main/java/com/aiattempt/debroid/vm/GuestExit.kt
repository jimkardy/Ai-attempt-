package com.aiattempt.debroid.vm

import com.aiattempt.debroid.log.Channel
import com.aiattempt.debroid.log.LogStore

/**
 * Explains a guest exit in plain words, using the phone's own logcat.
 *
 * When Android's app seccomp filter traps a system call the process dies with
 * SIGSYS, which a parent sees as exit code 159 (128 + 31). Bionic prints the
 * reason into logcat ("Fatal signal 31 (SIGSYS), code 1 (SYS_SECCOMP)"), and
 * that line is the only place that says *which* syscall was refused. The guest
 * shares this app's uid, so those lines land in the Logcat channel that
 * [com.aiattempt.debroid.log.LogcatPump] already streams.
 */
object GuestExit {

    private val markers = listOf("SIGSYS", "seccomp", "SYS_SECCOMP", "Fatal signal 31")

    /** The most recent logcat lines that mention a seccomp trap or SIGSYS. */
    fun trapLines(limit: Int = 12): List<String> =
        LogStore.lines(Channel.LOGCAT)
            .filter { line -> markers.any { line.contains(it, ignoreCase = true) } }
            .takeLast(limit)
            .map { it.trim() }

    /** What a shell-style exit code means for this guest. */
    fun explain(code: Int): String? = when (code) {
        159 ->
            "exit code 159 = 128 + 31 (SIGSYS): Android's app seccomp filter trapped a system call " +
                "the guest made, so the kernel was killed at its first userspace process. That is the " +
                "app sandbox refusing a syscall, not a broken image or a missing file."
        137 ->
            "exit code 137 = 128 + 9 (SIGKILL): the guest was killed from outside — normally Android's " +
                "low-memory killer, so give the VM less RAM or close other apps."
        139 -> "exit code 139 = 128 + 11 (SIGSEGV): the guest crashed on a memory fault."
        1 -> "exit code 1: the guest exited with an error. The console above says which."
        else -> null
    }

    /** The `--- seccomp ---` section of the debug report. */
    fun report(code: Int?): String {
        val lines = trapLines(limit = 20)
        return buildString {
            appendLine("last exit    : ${code ?: "none recorded"}")
            explain(code ?: -1)?.let { appendLine("meaning      : $it") }
            if (lines.isEmpty()) {
                appendLine("logcat traps : none captured (nothing in the logcat buffer mentions SIGSYS/seccomp)")
            } else {
                appendLine("logcat traps : ${lines.size} line(s)")
                lines.forEach { appendLine("  $it") }
            }
            appendLine("guest init   : static aarch64 binary, GLIBC_TUNABLES=glibc.pthread.rseq=0")
        }
    }
}
