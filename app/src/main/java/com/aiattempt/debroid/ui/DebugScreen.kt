package com.aiattempt.debroid.ui

import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
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
import com.aiattempt.debroid.shell.ShellSession
import com.aiattempt.debroid.sys.SelfTest
import com.aiattempt.debroid.vm.VmManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Debug tab: one square with every log that matters, one big copy button at
 * the top. Everything is local; sharing only happens when the user asks.
 */
class DebugScreen(private val activity: AppCompatActivity) {

    val root: View

    private val logView: TextView
    private val logScroll: ScrollView
    private val crashCard: View
    private val crashText: TextView

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

        // ---- the big one ---------------------------------------------------
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

        // ---- secondary actions ---------------------------------------------
        val actions = activity.row().apply { setPadding(activity.dpi(6), activity.dpi(2), activity.dpi(6), activity.dpi(8)) }
        actions.addView(activity.tonalButton("Self-test").apply { setOnClickListener { runSelfTest() } })
        actions.addView(activity.tonalButton("Share").apply {
            setOnClickListener { runCatching { Report.shareText(activity, buildReport()) } }
        })
        actions.addView(activity.tonalButton("ZIP").apply { setOnClickListener { saveZip() } })
        actions.addView(activity.tonalButton("Issue").apply { setOnClickListener { newIssue() } })
        actions.addView(activity.tonalButton("Clear").apply {
            setOnClickListener {
                LogStore.clearAll()
                lastRendered = ""
                renderLogs()
                toast("Logs cleared")
            }
        })
        actions.addView(activity.tonalButton("Host shell").apply { setOnClickListener { openHostShell() } })
        for (i in 0 until actions.childCount) {
            (actions.getChildAt(i).layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.setMargins(activity.dpi(3), 0, activity.dpi(3), 0)
            }
        }
        col.addView(activity.hScroll(actions))

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
            toast("Copied ${text.length} chars — paste it into the issue")
        } else {
            toast("Clipboard unavailable — use ZIP instead")
        }
    }

    private fun saveZip() {
        activity.lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching { Report.saveZip(activity, lastSelfTest) }.getOrNull()
            }
            if (file == null) {
                toast("Could not write the ZIP")
            } else {
                toast("ZIP ready: ${file.name}")
                Report.shareFile(activity, file)
            }
        }
    }

    private fun newIssue() {
        val text = buildReport()
        val copied = Report.copy(activity, text)
        val url = runCatching { Report.issueUrl(text) }.getOrElse { "https://github.com/${Report.REPO}/issues/new" }
        Report.openUrl(activity, url)
        toast(if (copied) "Report copied — paste it into the issue" else "Opened issue page")
    }

    private fun runSelfTest() {
        toast("Running self-test…")
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { SelfTest.run(activity) }.getOrElse { "self-test failed: ${it.javaClass.simpleName}: ${it.message}" }
            }
            lastSelfTest = result
            showSelfTestDialog(result)
            renderLogs()
        }
    }

    private fun showSelfTestDialog(text: String) {
        val view = activity.column()
        view.addView(
            activity.monoText(text, 10f).apply {
                setPadding(activity.dpi(14), activity.dpi(10), activity.dpi(14), activity.dpi(6))
            }
        )
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Self-test")
            .setView(ScrollView(activity).apply { addView(view) })
            .setPositiveButton("Copy") { _, _ ->
                if (Report.copy(activity, text)) toast("Self-test copied")
            }
            .setNegativeButton("Close", null)
            .create()
        dialog.show()
    }

    private fun openHostShell() {
        val home = File(activity.filesDir, "home").apply { mkdirs() }
        val col = activity.column().apply { setPadding(activity.dpi(8), activity.dpi(8), activity.dpi(8), 0) }
        val output = activity.monoText("", 10.5f)
        val shell = ShellSession(home) { line ->
            activity.runOnUiThread {
                output.append(if (output.text.isEmpty()) line else "\n$line")
                LogStore.log(Channel.SHELL, line)
            }
        }
        val scroll = ScrollView(activity).apply { addView(output) }
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dpi(280)))

        val input = EditText(activity).apply {
            hint = "host command"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
            textSize = 12f
        }
        col.addView(input)
        col.addView(
            activity.bodyText("Host shell runs /system/bin/sh as the app uid (no root, no pty).", 11f)
        )

        input.setOnEditorActionListener { _, _, _ ->
            val cmd = input.text.toString().trim()
            if (cmd.isNotEmpty()) {
                output.append("\n$ $cmd")
                shell.exec(cmd)
                input.setText("")
            }
            true
        }

        shell.start()
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Host shell (diagnostic)")
            .setView(col)
            .setNegativeButton("Close", null)
            .create()
        dialog.setOnDismissListener { shell.stop() }
        dialog.show()
    }

    private fun toast(message: String) {
        runCatching { Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }
    }
}
