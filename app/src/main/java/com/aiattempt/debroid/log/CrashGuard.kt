package com.aiattempt.debroid.log

/**
 * Installs a process-wide crash handler that writes the crash (thread, inclusion
 * stack trace, last log lines) to disk *before* the default handler kills the
 * process. The next launch surfaces it in the Debug tab and offers a one-tap copy.
 */
object CrashGuard {

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                LogStore.log(Channel.CRASH, "FATAL on thread '${thread.name}': ${error.javaClass.name}: ${error.message}")
                LogStore.logStack(Channel.CRASH, "stacktrace:", error)
                LogStore.recordCrash(thread, error)
            } catch (_: Throwable) {
                // never mask the original crash
            }
            try {
                previous?.uncaughtException(thread, error)
            } catch (_: Throwable) {
            }
        }
    }
}
