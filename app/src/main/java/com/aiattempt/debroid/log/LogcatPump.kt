package com.aiattempt.debroid.log

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
                    val process = ProcessBuilder("logcat", "-v", "threadtime")
                        .redirectErrorStream(true)
                        .start()
                    if (!announced) {
                        LogStore.log(Channel.APP, "logcat capture started (pid ${android.os.Process.myPid()})")
                        announced = true
                    }
                    process.inputStream.bufferedReader().useLines { seq ->
                        for (line in seq) {
                            if (line.isNotBlank()) LogStore.log(Channel.LOGCAT, line)
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
