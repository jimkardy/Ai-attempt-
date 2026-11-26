package com.aiattempt.debroid.ui

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.aiattempt.debroid.log.Channel
import com.aiattempt.debroid.log.LogStore
import com.aiattempt.debroid.report.Report
import com.aiattempt.debroid.sys.SelfTest
import com.aiattempt.debroid.sys.SysInfo
import com.aiattempt.debroid.vm.VmController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Debug tab: everything a maintainer would ask for, one tap away from the
 * clipboard. Every action is guarded so a failure here can never crash the app.
 */
class DebugScreen(private val activity: AppCompatActivity) {

    val root: View

    private val engineText: TextView
    private val logText: TextView
    private val logScroll: ScrollView
    private val selfTestText: TextView
    private val crashText: TextView
    private val crashCard: View
    private val channelSpinner: Spinner
    private val liveSwitch: SwitchCompat

    private var channel: Channel = Channel.APP
    private var lastSelfTest: String? = null
    private var started = false

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            runCatching { refreshLive() }
            if (started) handler.postDelayed(this, 1500)
        }
    }

    init {
        val col = activity.column()

        // ---- action row ----------------------------------------------------
        val actions = activity.row().apply { setPadding(activity.dpi(6), activity.dpi(8), activity.dpi(6), 0) }
        val actionsScroll = android.widget.HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(actions)
        }
        col.addView(actionsScroll)

        actions.addView(activity.actionButton("Copy everything").apply {
            setOnClickListener { copyAll() }
        })
        actions.addView(activity.actionButton("Share").apply {
            setOnClickListener { runCatching { Report.shareText(activity, buildReport()) } }
        })
        actions.addView(activity.actionButton("Save ZIP").apply {
            setOnClickListener { saveZip() }
        })
        actions.addView(activity.actionButton("New issue").apply {
            setOnClickListener { newIssue() }
        })

        // ---- crash banner --------------------------------------------------
        crashText = activity.monoText("", 10f)
        crashCard = activity.card {
            addView(activity.titleText("Last session crashed"))
            addView(crashText)
            val row = activity.row().apply { setPadding(activity.dpi(12), 0, activity.dpi(12), activity.dpi(10)) }
            row.addView(activity.actionButton("Copy crash").apply {
                setOnClickListener {
                    val text = LogStore.pendingCrash().orEmpty()
                    if (Report.copy(activity, text)) toast("Crash copied")
                }
            })
            row.addView(activity.actionButton("Dismiss").apply {
                setOnClickListener {
                    LogStore.acknowledgeCrash()
                    crashCard.visibility = View.GONE
                }
            })
            addView(row)
        }
        crashCard.visibility = View.GONE
        col.addView(crashCard)

        // ---- scroll body ---------------------------------------------------
        val body = activity.column()

        // device & build
        val device = activity.monoText(runCatching { SysInfo.report(activity) }.getOrDefault("unavailable"), 10f)
        body.addView(activity.card {
            addView(activity.titleText("Device & app"))
            addView(device)
        })

        // engine
        engineText = activity.monoText("", 10f)
        body.addView(activity.card {
            addView(activity.titleText("Engine (UML)"))
            addView(engineText)
            val row = activity.row().apply { setPadding(activity.dpi(12), 0, activity.dpi(12), activity.dpi(10)) }
            row.addView(activity.actionButton("Re-probe").apply {
                setOnClickListener {
                    activity.lifecycleScope.launch {
                        withContext(Dispatchers.IO) { runCatching { VmController.refresh(activity) } }
                        refreshLive()
                    }
                }
            })
            row.addView(activity.actionButton("Copy engine log").apply {
                setOnClickListener {
                    if (Report.copy(activity, LogStore.tail(Channel.ENGINE, 500))) toast("Engine log copied")
                }
            })
            addView(row)
        })

        // self test
        selfTestText = activity.monoText("Not run yet. Tap \"Run self-test\".", 10f)
        body.addView(activity.card {
            addView(activity.titleText("Self-test"))
            addView(selfTestText)
            val row = activity.row().apply { setPadding(activity.dpi(12), 0, activity.dpi(12), activity.dpi(10)) }
            row.addView(activity.actionButton("Run self-test").apply {
                setOnClickListener { runSelfTest() }
            })
            row.addView(activity.actionButton("Copy result").apply {
                setOnClickListener {
                    val text = lastSelfTest ?: selfTestText.text.toString()
                    if (Report.copy(activity, text)) toast("Self-test copied")
                }
            })
            addView(row)
        })

        // logs
        logText = activity.monoText("", 9f)
        logScroll = ScrollView(activity).apply {
            addView(logText)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                activity.dpi(260)
            )
        }
        channelSpinner = Spinner(activity).apply {
            adapter = ArrayAdapter(
                activity,
                android.R.layout.simple_spinner_dropdown_item,
                Channel.entries.map { it.label }
            )
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    channel = Channel.entries[position]
                    refreshLive()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        liveSwitch = SwitchCompat(activity).apply {
            text = "Live"
            textSize = 11f
            isChecked = true
            setPadding(activity.dpi(8), 0, activity.dpi(8), 0)
        }

        body.addView(activity.card {
            addView(activity.titleText("Logs"))
            val row = activity.row().apply { setPadding(activity.dpi(8), 0, activity.dpi(8), 0) }
            row.addView(channelSpinner)
            row.addView(liveSwitch)
            row.addView(activity.actionButton("Copy log").apply {
                setOnClickListener {
                    if (Report.copy(activity, LogStore.tail(channel, 1000))) toast("${channel.label} log copied")
                }
            })
            row.addView(activity.actionButton("Clear").apply {
                setOnClickListener {
                    LogStore.clearAll()
                    refreshLive()
                    toast("Logs cleared")
                }
            })
            addView(row)
            addView(logScroll)
        })

        body.addView(activity.spacer(24))

        val bodyScroll = ScrollView(activity).apply { addView(body) }
        col.addView(bodyScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        col.addView(
            activity.monoText(
                "Everything above is local. Nothing is uploaded unless you tap Share / New issue.",
                9f
            )
        )

        root = col
    }

    fun start() {
        started = true
        runCatching { showPendingCrash() }
        runCatching { refreshLive() }
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 600)
    }

    fun stop() {
        started = false
        handler.removeCallbacks(ticker)
    }

    // ---- actions -----------------------------------------------------------

    private fun buildReport(): String = runCatching {
        Report.full(activity, lastSelfTest)
    }.getOrElse { "report failed: ${it.javaClass.simpleName}: ${it.message}\n\n" + LogStore.fullText().take(20000) }

    private fun copyAll() {
        val text = buildReport()
        if (Report.copy(activity, text)) {
            toast("Full report copied (${text.length} chars) — paste it into the issue")
        } else {
            toast("Clipboard unavailable — use Save ZIP instead")
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
        selfTestText.text = "running…"
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { SelfTest.run(activity) }.getOrElse { "self-test failed: ${it}" } }
            lastSelfTest = result
            selfTestText.text = result
            toast("Self-test finished")
        }
    }

    // ---- live refresh ------------------------------------------------------

    private fun refreshLive() {
        engineText.text = VmController.statusText()
        if (liveSwitch.isChecked) {
            val atBottom = logScroll.canScrollVertically(1).not()
            logText.text = LogStore.tail(channel, 250).ifEmpty { "(no ${channel.label} log yet)" }
            if (atBottom) logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
        showPendingCrash()
    }

    private fun showPendingCrash() {
        val pending = LogStore.pendingCrash()
        if (pending.isNullOrBlank()) {
            crashCard.visibility = View.GONE
        } else {
            crashText.text = pending.takeLast(4000)
            crashCard.visibility = View.VISIBLE
        }
    }

    private fun toast(message: String) {
        runCatching { Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }
    }
}
