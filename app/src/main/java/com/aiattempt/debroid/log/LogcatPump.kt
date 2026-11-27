package com.aiattempt.debroid.log

import com.aiattempt.debroid.vm.GuestExit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Streams `logcat -v threadtime` into [LogStore]; restarts itself if logcat dies. */
class LogcatPump(private val scope: CoroutineScope) {

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            var announced = false
            while (isActive) {
                try {
                    // `-b all` includes the crash buffer, which is where the
                    // tombstone naming a seccomp-refused syscall lands.
                    val process = ProcessBuilder("logcat", "-b", "all", "-v", "threadtime")
                        .redirectErrorStream(true)
                        .start()
                    if (!announced) {
                        LogStore.log(Channel.APP, "logcat capture started (pid ${android.os.Process.myPid()})")
                        announced = true
                    }
                    process.inputStream.bufferedReader().useLines { seq ->
                        for (line in seq) {
                            if (line.isNotBlank()) {
                                LogStore.log(Channel.LOGCAT, line)
                                GuestExit.record(line)
                            }
                        }
                    }
                    process.waitFor()
                } catch (t: Throwable) {
                    LogStore.log(Channel.APP, "logcat reader stopped: ${t.javaClass.simpleName}: ${t.message}")
                }
                delay(3000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
