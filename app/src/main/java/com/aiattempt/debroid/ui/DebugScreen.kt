package com.aiattempt.debroid.ui

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.aiattempt.debroid.R
import com.aiattempt.debroid.log.Channel
import com.aiattempt.debroid.log.LogStore
import com.aiattempt.debroid.report.Report
import com.aiattempt.debroid.sys.SelfTest
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Debug tab: one square with every log that matters, one big copy button at
 * the top (the owner pastes it straight into a chat) and one big button at the
 * bottom for everyone else — it runs the self-test, copies the full report and
 * opens a GitHub issue with it. Everything is local until the user sends it.
 */
class DebugScreen(private val activity: AppCompatActivity) {

    val root: View

    private val logView: TextView
    private val logScroll: ScrollView
    private val crashCard: View
    private val crashText: TextView
    private val sendButton: MaterialButton

    private var channel: Channel? = null // null == ALL
    private val channelButtons = ArrayList<Pair<MaterialButton, Channel?>>()
    private var lastRendered = ""
    private var lastSelfTest: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (root.isAttachedToWindow) runCatching { renderLogs() }
            handler.postDelayed(this, 1500)
        }
    }

    init {
        val col = activity.column()

        // ---- the big one (for the owner: copy and paste) --------------------
        val copyWrap = activity.column().apply {
            setPadding(activity.dpi(12), activity.dpi(8), activity.dpi(12), 0)
        }
        copyWrap.addView(
            activity.primaryButton("COPY EVERYTHING").apply {
                textSize = 15f
                setOnClickListener { copyEverything() }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dpi(56))
        )
        col.addView(copyWrap)

        // ---- crash banner --------------------------------------------------
        crashText = activity.monoText("", 10.5f)
        crashCard = activity.card {
            addView(activity.titleText("Last session crashed").apply { textSize = 16f })
            addView(crashText)
            addView(
                activity.actionRow(
                    activity.tonalButton("Copy crash").apply {
                        setOnClickListener {
                            val text = LogStore.pendingCrash().orEmpty()
                            if (Report.copy(activity, text)) toast("Crash copied")
                        }
                    },
                    activity.tonalButton("Dismiss").apply {
                        setOnClickListener {
                            LogStore.acknowledgeCrash()
                            crashCard.visibility = View.GONE
                        }
                    }
                )
            )
        }
        crashCard.visibility = View.GONE
        col.addView(crashCard)

        // ---- channel selector ----------------------------------------------
        val chips = activity.row().apply { setPadding(activity.dpi(6), activity.dpi(2), activity.dpi(6), 0) }
        addChannelButton(chips, "ALL", null)
        Channel.entries.forEach { ch -> addChannelButton(chips, ch.label, ch) }
        col.addView(activity.hScroll(chips))

        // ---- log panel -----------------------------------------------------
        logView = activity.monoText("", 10.5f).apply {
            setPadding(activity.dpi(12), activity.dpi(8), activity.dpi(12), activity.dpi(12))
        }
        logScroll = ScrollView(activity).apply {
            addView(logView)
            setBackgroundColor(activity.color(R.color.surface))
        }
        val panel = activity.card {
            setPadding(0, activity.dpi(6), 0, activity.dpi(6))
            addView(logScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dpi(360)))
        }
        col.addView(panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- one button for everyone else ----------------------------------
        val sendWrap = activity.column().apply {
            setPadding(activity.dpi(12), activity.dpi(4), activity.dpi(12), activity.dpi(10))
        }
        sendButton = activity.primaryButton("Having issues? Send the report to our team on GitHub").apply {
            textSize = 14f
            setOnClickListener { sendToGithub() }
        }
        sendWrap.addView(
            sendButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dpi(60))
        )
        sendWrap.addView(
            TextView(activity).apply {
                text = "Runs the self-test, copies the full report and opens a new GitHub issue with it. " +
                    "Nothing leaves your phone until you press Send there."
                textSize = 11f
                setTextColor(activity.color(R.color.on_surface_3))
                setPadding(activity.dpi(4), activity.dpi(6), activity.dpi(4), 0)
            }
        )
        col.addView(sendWrap)

        root = col
        renderLogs()
    }

    fun refresh() {
        if (!root.isAttachedToWindow) return
        runCatching { renderLogs() }
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 1500)
    }

    // ---- rendering --------------------------------------------------------

    private fun addChannelButton(parent: LinearLayout, label: String, ch: Channel?) {
        val b = activity.tonalButton(label).apply {
            textSize = 12f
            minHeight = activity.dpi(38)
            setOnClickListener {
                channel = ch
                styleChannels()
                lastRendered = ""
                renderLogs()
            }
        }
        channelButtons.add(b to ch)
        parent.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, activity.dpi(38)).apply {
            setMargins(activity.dpi(3), 0, activity.dpi(3), 0)
        })
    }

    private fun styleChannels() {
        channelButtons.forEach { (b, ch) ->
            if (ch == channel) {
                b.backgroundTintList = android.content.res.ColorStateList.valueOf(activity.color(R.color.red))
                b.setTextColor(activity.color(R.color.on_red))
            } else {
                b.backgroundTintList = android.content.res.ColorStateList.valueOf(activity.color(R.color.surface_2))
                b.setTextColor(activity.color(R.color.on_surface_2))
            }
        }
    }

    private fun renderLogs() {
        styleChannels()
        val text = when (val ch = channel) {
            null -> buildReport()
            else -> LogStore.tail(ch, 600).ifEmpty { "(no ${ch.label} log yet)" }
        }
        val trimmed = if (text.length > 60_000) text.takeLast(60_000) else text
        if (trimmed == lastRendered) return
        val atBottom = !logScroll.canScrollVertically(1)
        lastRendered = trimmed
        logView.text = trimmed
        if (atBottom) logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        showPendingCrash()
    }

    private fun buildReport(): String = runCatching {
        Report.full(activity, lastSelfTest)
    }.getOrElse {
        "report failed: ${it.javaClass.simpleName}: ${it.message}\n\n" + LogStore.fullText().take(20000)
    }

    private fun showPendingCrash() {
        val pending = LogStore.pendingCrash()
        if (pending.isNullOrBlank()) {
            crashCard.visibility = View.GONE
        } else {
            crashText.text = pending.takeLast(2500)
            crashCard.visibility = View.VISIBLE
        }
    }

    // ---- actions ----------------------------------------------------------

    private fun copyEverything() {
        val text = buildReport()
        if (Report.copy(activity, text)) {
            toast("Copied ${text.length} chars — paste it where it belongs")
        } else {
            toast("Clipboard unavailable")
        }
    }

    /**
     * The one action for a normal user: run the self-test so the report is
     * complete, copy it (so a paste is always possible) and open a new issue
     * with the important part already filled in.
     */
    private fun sendToGithub() {
        sendButton.isEnabled = false
        sendButton.text = "Preparing the report…"
        activity.lifecycleScope.launch {
            val selfTest = withContext(Dispatchers.IO) {
                runCatching { SelfTest.run(activity) }
                    .getOrElse { "self-test failed: ${it.javaClass.simpleName}: ${it.message}" }
            }
            lastSelfTest = selfTest
            val text = buildReport()
            val copied = Report.copy(activity, text)
            val url = runCatching { Report.issueUrl(text) }
                .getOrElse { "https://github.com/${Report.REPO}/issues/new" }
            Report.openUrl(activity, url)
            sendButton.isEnabled = true
            sendButton.text = "Having issues? Send the report to our team on GitHub"
            toast(
                if (copied) "Issue opened — the full report is on your clipboard, paste it in if asked"
                else "Issue opened — use Copy everything above to attach the details"
            )
        }
    }

    private fun toast(message: String) {
        runCatching { Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }
    }
}
