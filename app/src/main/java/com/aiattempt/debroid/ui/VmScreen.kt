package com.aiattempt.debroid.ui

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.aiattempt.debroid.log.LogStore
import com.aiattempt.debroid.vm.VmController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** VM tab: engine state, probe results and the engine log tail. */
class VmScreen(private val activity: AppCompatActivity) {

    val root: View
    private val status: TextView
    private val logTail: TextView

    init {
        val col = activity.column()

        col.addView(activity.titleText("Debian via User-Mode Linux"))
        status = activity.monoText("probing…", 11f)
        col.addView(status)

        val buttons = activity.row().apply { setPadding(activity.dpi(10), 0, activity.dpi(10), 0) }
        val refreshBtn = activity.actionButton("Re-probe")
        val startBtn = activity.actionButton("Start VM")
        val stopBtn = activity.actionButton("Stop")
        buttons.addView(refreshBtn)
        buttons.addView(startBtn)
        buttons.addView(stopBtn)
        col.addView(buttons)

        col.addView(
            activity.monoText(
                "v1 ships the diagnostics shell only: the arm64 UML kernel and the Debian squashfs are\n" +
                    "built by CI in the next milestone. Start is wired to the supervisor contract, so it\n" +
                    "reports exactly what is missing instead of failing.",
                10f
            )
        )

        col.addView(activity.titleText("Engine log (tail)"))
        logTail = activity.monoText("", 10f)
        val logScroll = android.widget.ScrollView(activity).apply { addView(logTail) }
        col.addView(logScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        refreshBtn.setOnClickListener { probe() }
        startBtn.setOnClickListener {
            val message = VmController.start(activity)
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            render()
        }
        stopBtn.setOnClickListener {
            VmController.stop()
            render()
        }

        root = col
    }

    fun start() {
        probe()
    }

    fun stop() {}

    private fun probe() {
        activity.lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { VmController.refresh(activity) } }
            render()
        }
    }

    private fun render() {
        status.text = VmController.statusText()
        logTail.text = LogStore.tail(com.aiattempt.debroid.log.Channel.ENGINE, 60).ifEmpty { "(no engine log yet)" }
    }
}
