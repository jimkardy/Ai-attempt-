package com.aiattempt.debroid.log

import com.aiattempt.debroid.vm.GuestExit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Streams **this app's own** logcat lines into [LogStore]; restarts itself if
 * logcat dies.
 *
 * The filter is `--uid=<our uid>`, not the default firehose: logcat's default
 * stream carries every other app and system service on the phone, and none of
 * that belongs in a report. The uid filter keeps the app's own processes —
 * including the UML kernel process it spawns — and drops everything else, so
 * the log panel and the debug report can never show another app's data.
 */
class LogcatPump(private val scope: CoroutineScope) {

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            var announced = false
            while (isActive) {
                try {
                    // `--uid` limits the stream to this app (main, system and
                    // crash buffers are all filtered): other apps' logs stay on
                    // the device, where they belong.
                    val process = ProcessBuilder(
                        "logcat", "--uid=${android.os.Process.myUid()}", "-v", "threadtime"
                    )
                        .redirectErrorStream(true)
                        .start()
                    if (!announced) {
                        LogStore.log(
                            Channel.APP,
                            "logcat capture started (pid ${android.os.Process.myPid()}, " +
                                "uid ${android.os.Process.myUid()} only)"
                        )
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
