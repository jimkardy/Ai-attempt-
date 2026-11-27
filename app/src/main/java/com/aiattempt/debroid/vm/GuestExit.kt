package com.aiattempt.debroid.vm

import com.aiattempt.debroid.log.Channel
import com.aiattempt.debroid.log.LogStore

/**
 * Explains a guest exit in plain words, using the phone's own logcat.
 *
 * When Android's app seccomp filter traps a system call the process dies with
 * SIGSYS, which a parent sees as exit code 159 (128 + 31). The one place that
 * says *which* syscall was refused is the tombstone bionic writes into the
 * `crash` log buffer ("Cause: seccomp prevented call to disallowed arm64 system
 * call 92"). That buffer is not part of the normal logcat stream, it rotates
 * away quickly, and the Logcat channel is trimmed with the rest of the logs, so
 * every matching line is also copied into a small ring here and a snapshot of
 * the crash buffer is taken the moment the kernel process exits.
 */
object GuestExit {

    private val markers = listOf("SIGSYS", "SYS_SECCOMP", "disallowed", "Fatal signal 31")
    private const val RING = 60

    private val ring = ArrayDeque<String>()

    /** True for the logcat lines that identify a trapped system call. */
    fun looksLikeTrap(line: String): Boolean =
        markers.any { line.contains(it, ignoreCase = true) }

    /** Keeps a trap line even after the Logcat channel rotates. */
    fun record(line: String) {
        val text = line.trim()
        if (text.isEmpty() || !looksLikeTrap(text)) return
        synchronized(ring) {
            if (ring.contains(text)) return
            ring.addLast(text)
            while (ring.size > RING) ring.removeFirst()
        }
    }

    /**
     * Copies the trap lines out of logcat's own buffers. Called when the kernel
     * process exits, before anything else reads the report: the tombstone is
     * the only record of the refused syscall number.
     */
    fun captureTombstones(): List<String> {
        val found = ArrayList<String>()
        val commands = listOf(
            arrayOf("logcat", "-b", "crash", "-d", "-v", "threadtime", "-t", "400"),
            arrayOf("logcat", "-b", "main", "-d", "-v", "threadtime", "-t", "1500")
        )
        for (command in commands) {
            runCatching {
                val process = ProcessBuilder(*command).redirectErrorStream(true).start()
                process.inputStream.bufferedReader().useLines { seq ->
                    seq.forEach { line -> if (looksLikeTrap(line)) found.add(line.trim()) }
                }
                process.waitFor()
            }
        }
        found.forEach { record(it) }
        return found
    }

    /** The most recent lines that mention a seccomp trap or SIGSYS. */
    fun trapLines(limit: Int = 20): List<String> {
        val merged = LinkedHashSet<String>()
        synchronized(ring) { merged.addAll(ring) }
        // The durable ring first, then whatever the logcat channel still holds.
        LogStore.lines(Channel.LOGCAT)
            .filter { line -> markers.any { line.contains(it, ignoreCase = true) } }
            .map { it.trim() }
            .forEach { merged.add(it) }
        return merged.toList().takeLast(limit)
    }

    private val causeNumber = Regex("disallowed\\s+arm(?:64)?\\s+system\\s+call\\s+(\\d+)")

    /** The syscall number Android refused, when a tombstone survived. */
    fun blockedSyscall(): Pair<Int, String>? {
        for (line in trapLines(limit = 60).asReversed()) {
            val number = causeNumber.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            return number to syscallName(number)
        }
        return null
    }

    /** arm64 (generic) syscall numbers, for the calls worth naming in a report. */
    fun syscallName(nr: Int): String = when (nr) {
        39 -> "umount2"
        40 -> "mount"
        41 -> "pivot_root"
        42 -> "nfsservctl"
        51 -> "chroot"
        60 -> "quotactl"
        89 -> "acct"
        92 -> "personality"
        96 -> "set_tid_address"
        97 -> "unshare"
        98 -> "futex"
        99 -> "set_robust_list"
        100 -> "get_robust_list"
        104 -> "kexec_load"
        105 -> "init_module"
        106 -> "delete_module"
        112 -> "clock_settime"
        116 -> "syslog"
        117 -> "ptrace"
        119 -> "sched_setscheduler"
        122 -> "sched_setaffinity"
        141 -> "setpriority"
        142 -> "reboot"
        143 -> "setregid"
        144 -> "setgid"
        145 -> "setreuid"
        146 -> "setuid"
        147 -> "setresuid"
        149 -> "setresgid"
        151 -> "setfsuid"
        152 -> "setfsgid"
        159 -> "setgroups"
        161 -> "sethostname"
        162 -> "setdomainname"
        167 -> "prctl"
        170 -> "settimeofday"
        171 -> "adjtimex"
        172 -> "adjtimex"
        217 -> "add_key"
        218 -> "request_key"
        219 -> "keyctl"
        220 -> "clone"
        221 -> "execve"
        224 -> "swapon"
        225 -> "swapoff"
        235 -> "mbind"
        236 -> "get_mempolicy"
        238 -> "migrate_pages"
        239 -> "move_pages"
        241 -> "perf_event_open"
        268 -> "setns"
        270 -> "process_vm_readv"
        271 -> "process_vm_writev"
        277 -> "seccomp"
        278 -> "getrandom"
        280 -> "bpf"
        282 -> "userfaultfd"
        293 -> "rseq"
        425 -> "io_uring_setup"
        434 -> "pidfd_open"
        435 -> "clone3"
        else -> "syscall"
    }

    /** What a shell-style exit code means for this guest. */
    fun explain(code: Int): String? = when (code) {
        159 ->
            "exit code 159 = 128 + 31 (SIGSYS): Android's app seccomp filter trapped a system call " +
                "made by the kernel process, so the guest was killed where it stood. The " +
                "\"--- seccomp ---\" section names the syscall when logcat kept the tombstone."
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
        val blocked = blockedSyscall()
        return buildString {
            appendLine("last exit    : ${code ?: "none recorded"}")
            explain(code ?: -1)?.let { appendLine("meaning      : $it") }
            if (blocked != null) {
                appendLine("blocked call : ${blocked.second} (arm64 syscall ${blocked.first})")
            } else {
                appendLine("blocked call : not named by logcat — the tombstone had rotated away or " +
                    "crash_dump could not attach")
            }
            appendLine("userspace    : ptrace (the kernel's seccomp mode cannot install its own filter " +
                "in an Android app: the seccomp() syscall is itself trapped)")
            if (lines.isEmpty()) {
                appendLine("logcat traps : none captured (nothing mentions SIGSYS/seccomp)")
            } else {
                appendLine("logcat traps : ${lines.size} line(s)")
                lines.forEach { appendLine("  $it") }
            }
            appendLine("guest init   : static aarch64 binary, GLIBC_TUNABLES=glibc.pthread.rseq=0")
        }
    }
}
