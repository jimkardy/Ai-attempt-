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
import androidx.appcompat.app.AppCompatActivity
import com.aiattempt.debroid.R
import com.aiattempt.debroid.report.Report
import com.aiattempt.debroid.vm.VmInstance
import com.aiattempt.debroid.vm.VmManager

/**
 * The guest terminal. It shows a Start button while nothing runs, and the live
 * console of the running VM otherwise — one VM, one console, no host shell here.
 */
class TerminalScreen(
    private val activity: AppCompatActivity,
    private val onOpenVmTab: () -> Unit
) {

    val root: View = activity.column()

    private val handler = Handler(Looper.getMainLooper())
    private var renderedKey = ""
    private var consoleView: TextView? = null
    private var consoleScroll: ScrollView? = null
    private var inputField: EditText? = null
    private var attachedVmId: String? = null
    private var lastConsole = ""

    private val ticker = object : Runnable {
        override fun run() {
            if (!root.isAttachedToWindow) return
            runCatching { refresh() }
            handler.postDelayed(this, 1000)
        }
    }

    init {
        (root as LinearLayout).setBackgroundColor(activity.color(R.color.bg))
        refresh()
    }

    fun refresh() {
        if (!root.isAttachedToWindow && renderedKey.isNotEmpty()) return
        val running = VmManager.running(activity)
        val vms = VmManager.list(activity)
        val key = "${running?.id}|${running?.state}|${vms.size}|${vms.count { it.state == com.aiattempt.debroid.vm.VmState.RUNNING }}"
        if (key != renderedKey) {
            renderedKey = key
            build(running, vms)
        } else if (running != null) {
            updateConsole(running)
        }
        startTicker()
    }

    private fun startTicker() {
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 1000)
    }

    private fun build(running: VmInstance?, vms: List<VmInstance>) {
        val col = root as LinearLayout
        col.removeAllViews()
        consoleView = null
        consoleScroll = null
        inputField = null
        attachedVmId = running?.id
        lastConsole = ""

        if (running == null) {
            col.addView(noVmState(vms))
        } else {
            col.addView(consoleHeader(running))
            col.addView(consoleBody(running), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            col.addView(inputRow(running))
        }
    }

    // ---- no VM running ----------------------------------------------------

    private fun noVmState(vms: List<VmInstance>): View {
        val scroll = ScrollView(activity)
        val col = activity.column()
        col.addView(
            activity.card {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(activity.dpi(8), activity.dpi(28), activity.dpi(8), activity.dpi(20))
                addView(
                    TextView(activity).apply {
                        text = "No VM is running"
                        textSize = 22f
                        setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                        setTextColor(activity.color(R.color.on_surface))
                        gravity = Gravity.CENTER
                    }
                )
                addView(
                    TextView(activity).apply {
                        text = if (vms.isEmpty())
                            "Create a VM on the VM tab — the terminal opens here as soon as it boots."
                        else "Start a VM and this tab becomes its console."
                        textSize = 13f
                        setTextColor(activity.color(R.color.on_surface_2))
                        gravity = Gravity.CENTER
                        setPadding(activity.dpi(20), activity.dpi(8), activity.dpi(20), activity.dpi(16))
                    }
                )
                vms.forEach { vm ->
                    addView(
                        activity.primaryButton("Start ${vm.name}").apply {
                            setOnClickListener {
                                VmManager.start(activity, vm.id)
                                    .onFailure {
                                        android.widget.Toast.makeText(activity, it.message, android.widget.Toast.LENGTH_LONG).show()
                                    }
                                refresh()
                            }
                        },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dpi(48)).apply {
                            setMargins(activity.dpi(20), activity.dpi(4), activity.dpi(20), activity.dpi(4))
                        }
                    )
                }
                if (vms.isEmpty()) {
                    addView(
                        activity.primaryButton("Open VM tab").apply {
                            setOnClickListener { onOpenVmTab() }
                        },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dpi(48)).apply {
                            setMargins(activity.dpi(20), activity.dpi(4), activity.dpi(20), activity.dpi(4))
                        }
                    )
                }
            }
        )
        scroll.addView(col)
        return scroll
    }

    // ---- running VM -------------------------------------------------------

    private fun consoleHeader(vm: VmInstance): View = activity.card {
        val row = activity.row().apply { setPadding(activity.dpi(14), activity.dpi(10), activity.dpi(14), activity.dpi(6)) }
        row.addView(
            TextView(activity).apply {
                text = vm.name
                textSize = 16f
                setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                setTextColor(activity.color(R.color.on_surface))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(activity.pill(vm.state.name, activity.color(R.color.ok)))
        addView(row)
        addView(
            TextView(activity).apply {
                text = vm.specLine + "  ·  up " + uptime(vm.uptimeSeconds())
                textSize = 11.5f
                setTextColor(activity.color(R.color.on_surface_3))
                setPadding(activity.dpi(14), 0, activity.dpi(14), activity.dpi(8))
            }
        )
        val actions = activity.row().apply { setPadding(activity.dpi(12), 0, activity.dpi(12), activity.dpi(10)) }
        actions.addView(activity.tonalButton("Restart").apply {
            setOnClickListener { VmManager.restart(activity, vm.id); refresh() }
        }, LinearLayout.LayoutParams(0, activity.dpi(40), 1f).apply { setMargins(activity.dpi(2), 0, activity.dpi(2), 0) })
        actions.addView(activity.tonalButton("Stop").apply {
            setOnClickListener { VmManager.stop(activity, vm.id); refresh() }
        }, LinearLayout.LayoutParams(0, activity.dpi(40), 1f).apply { setMargins(activity.dpi(2), 0, activity.dpi(2), 0) })
        actions.addView(activity.tonalButton("Copy log").apply {
            setOnClickListener {
                if (Report.copy(activity, VmManager.consoleTail(activity, vm.id, 1500))) {
                    android.widget.Toast.makeText(activity, "Console copied", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }, LinearLayout.LayoutParams(0, activity.dpi(40), 1f).apply { setMargins(activity.dpi(2), 0, activity.dpi(2), 0) })
        addView(actions)
    }

    private fun consoleBody(vm: VmInstance): View {
        val view = activity.monoText("", 11f).apply {
            setPadding(activity.dpi(14), activity.dpi(10), activity.dpi(14), activity.dpi(14))
            setBackgroundColor(activity.color(R.color.bg))
        }
        val scroll = ScrollView(activity).apply {
            addView(view)
            setBackgroundColor(activity.color(R.color.bg))
        }
        consoleView = view
        consoleScroll = scroll
        updateConsole(vm)
        return scroll
    }

    private fun updateConsole(vm: VmInstance) {
        val text = VmManager.consoleTail(activity, vm.id, 600).ifEmpty {
            "waiting for guest output…\n\n" + VmManager.list(activity).firstOrNull { it.id == vm.id }?.message.orEmpty()
        }
        if (text == lastConsole) return
        val view = consoleView ?: return
        val scroll = consoleScroll
        val atBottom = scroll == null || !scroll.canScrollVertically(1)
        val trimmed = if (text.length > 120_000) text.takeLast(60_000) else text
        view.text = trimmed
        lastConsole = text
        if (atBottom) scroll?.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun inputRow(vm: VmInstance): View {
        val row = activity.row().apply {
            setPadding(activity.dpi(10), activity.dpi(8), activity.dpi(10), activity.dpi(10))
            setBackgroundColor(activity.color(R.color.bg))
        }
        val field = EditText(activity).apply {
            hint = "type a command"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
            textSize = 13f
            setTextColor(activity.color(R.color.on_surface))
            setHintTextColor(activity.color(R.color.on_surface_3))
            setPadding(activity.dpi(12), activity.dpi(10), activity.dpi(12), activity.dpi(10))
            setBackgroundColor(activity.color(R.color.surface_2))
        }
        inputField = field
        row.addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        fun submit() {
            val text = field.text.toString()
            if (text.isEmpty()) return
            val ok = VmManager.sendInput(activity, vm.id, text)
            field.setText("")
            if (!ok) {
                android.widget.Toast.makeText(activity, "The guest is not accepting input yet", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        row.addView(
            activity.primaryButton("Run").apply { setOnClickListener { submit() } },
            LinearLayout.LayoutParams(activity.dpi(84), activity.dpi(44)).apply { setMargins(activity.dpi(8), 0, 0, 0) }
        )
        return row
    }

    private fun uptime(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
}
