package com.aiattempt.debroid.vm

import android.content.Context
import com.aiattempt.debroid.log.Channel
import com.aiattempt.debroid.log.LogStore
import com.aiattempt.debroid.sys.SysInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Owns the UML VM lifecycle. v1 of the debug build does not ship the arm64 UML
 * kernel or the Debian squashfs yet, so every entry point reports precisely what
 * is missing instead of failing. The hooks here are what the real supervisor
 * plugs into in v2.
 */
object VmController {

    enum class State { PROBING, NOT_BUNDLED, READY, STARTING, RUNNING, STOPPED_WITH_ERROR }

    private val requiredAssets = listOf(
        "uml/libuml-4k.so",
        "uml/libuml-16k.so",
        "rootfs/rootfs.squashfs"
    )

    @Volatile
    var state: State = State.PROBING
        private set

    @Volatile
    var detail: String = "not probed yet"
        private set

    @Volatile
    var lastProbe: Long = 0L
        private set

    fun kernelForThisDevice(): String = if (SysInfo.pageSizeKb() == 16) "libuml-16k.so" else "libuml-4k.so"

    @Synchronized
    fun refresh(ctx: Context) {
        val missing = ArrayList<String>()
        val present = ArrayList<String>()
        requiredAssets.forEach { path ->
            val ok = try {
                ctx.assets.open(path).use { true }
            } catch (_: Throwable) {
                false
            }
            if (ok) present.add(path) else missing.add(path)
        }
        lastProbe = System.currentTimeMillis()
        state = if (missing.isEmpty()) State.READY else State.NOT_BUNDLED
        detail = buildString {
            appendLine("kernel for this device: ${kernelForThisDevice()} (page size ${SysInfo.pageSizeKb()} KB)")
            appendLine("probe: ${stamp()}")
            if (present.isNotEmpty()) appendLine("present: ${present.joinToString(", ")}")
            if (missing.isNotEmpty()) {
                appendLine("missing: ${missing.joinToString(", ")}")
                appendLine("v1 is the diagnostics shell: the UML supervisor, arm64 kernel and Debian rootfs")
                appendLine("are built by CI in the next milestone and dropped into assets/uml + assets/rootfs.")
            }
        }
        LogStore.log(Channel.ENGINE, "asset probe: state=$state missing=${missing.size}")
    }

    @Synchronized
    fun start(ctx: Context): String {
        if (state == State.PROBING || lastProbe == 0L) refresh(ctx)
        val message = when (state) {
            State.READY -> {
                state = State.STARTING
                "Booting UML is not implemented in this build yet. The bundle is present; the supervisor lands in v2."
            }
            State.NOT_BUNDLED ->
                "Cannot start: this build does not contain the UML kernel or the Debian rootfs. Nothing to boot yet."
            State.RUNNING -> "VM is already running."
            else -> "Cannot start in state $state."
        }
        state = if (state == State.STARTING) State.NOT_BUNDLED else state
        detail = message
        LogStore.log(Channel.ENGINE, "start() -> $message")
        return message
    }

    @Synchronized
    fun stop() {
        state = if (state == State.RUNNING) State.NOT_BUNDLED else state
        detail = "stopped"
        LogStore.log(Channel.ENGINE, "stop() requested; nothing running")
    }

    fun statusText(): String = buildString {
        appendLine("state   : $state")
        appendLine("probed  : ${if (lastProbe == 0L) "never" else stampOf(lastProbe)}")
        appendLine("kernel  : ${kernelForThisDevice()}")
        appendLine()
        append(detail)
    }

    private fun stamp(): String = stampOf(System.currentTimeMillis())

    private fun stampOf(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))
}
