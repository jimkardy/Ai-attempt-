package com.aiattempt.debroid

import android.app.Application
import android.os.Build
import com.aiattempt.debroid.log.Channel
import com.aiattempt.debroid.log.CrashGuard
import com.aiattempt.debroid.log.LogStore
import com.aiattempt.debroid.log.LogcatPump
import com.aiattempt.debroid.sys.DeviceSupport
import com.aiattempt.debroid.vm.VmManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Everything here is individually guarded: a failure in diagnostics must never be
 * able to stop the app from starting.
 */
class DebroidApp : Application() {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var logcat: LogcatPump? = null

    override fun onCreate() {
        super.onCreate()
        runCatching { LogStore.init(this) }
        runCatching { CrashGuard.install() }
        runCatching { VmManager.init(this) }
        runCatching { File(filesDir, "home").mkdirs() }
        runCatching { logcat = LogcatPump(scope).also { it.start() } }

        LogStore.log(
            Channel.APP,
            "Debroid ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) started — " +
                "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}, " +
                "abi ${Build.SUPPORTED_ABIS.firstOrNull()}"
        )
        runCatching { LogStore.log(Channel.APP, "support: ${DeviceSupport.summary(this)}") }
    }
}
