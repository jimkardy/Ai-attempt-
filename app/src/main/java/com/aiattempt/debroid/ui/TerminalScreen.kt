package com.aiattempt.debroid.ui

import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.aiattempt.debroid.R
import com.aiattempt.debroid.log.Channel
import com.aiattempt.debroid.log.LogStore
import com.aiattempt.debroid.shell.ShellSession
import com.google.android.material.button.MaterialButton

/** Terminal tab: interactive host shell (app uid) with quick-command buttons. */
class TerminalScreen(private val activity: AppCompatActivity) {

    val root: View
    private val output: TextView
    private val scroll: ScrollView
    private val input: EditText
    private val shell = ShellSession { line ->
        activity.runOnUiThread { appendLine(line) }
    }

    init {
        val col = activity.column()

        val header = activity.row().apply { setPadding(activity.dpi(10), activity.dpi(8), activity.dpi(10), 0) }
        val startBtn = activity.actionButton("Start")
        val stopBtn = activity.actionButton("Stop")
        val clearBtn = activity.actionButton("Clear")
        header.addView(startBtn)
        header.addView(stopBtn)
        header.addView(clearBtn)
        col.addView(header)

        val hint = activity.monoText(
            "Host shell: /system/bin/sh as the app uid (no root, no pty).\n" +
                "The Debian guest console arrives with the UML engine.",
            10f
        )
        col.addView(hint)

        output = activity.monoText("", 10f)
        scroll = ScrollView(activity).apply { addView(output) }
        col.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val quick = activity.row().apply { setPadding(activity.dpi(10), 0, activity.dpi(10), 0) }
        listOf("id", "getprop | head -20", "uname -a", "ls -l /data/data/${activity.packageName}").forEach { cmd ->
            quick.addView(activity.actionButton(cmd).apply {
                textSize = 9f
                setOnClickListener { send(cmd) }
            })
        }
        col.addView(quick)

        val inputRow = activity.row().apply { setPadding(activity.dpi(10), activity.dpi(4), activity.dpi(10), activity.dpi(10)) }
        input = EditText(activity).apply {
            setHint("command")
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setSingleLine()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnEditorActionListener { _, _, _ ->
                send(text.toString()); true
            }
        }
        val sendBtn = MaterialButton(activity).apply {
            setText("Run")
            isAllCaps = false
            minWidth = 0
            setOnClickListener { send(input.text.toString()) }
        }
        inputRow.addView(input)
        inputRow.addView(sendBtn)
        col.addView(inputRow)

        startBtn.setOnClickListener {
            if (shell.start()) appendLine("[shell ready]")
        }
        stopBtn.setOnClickListener {
            shell.stop()
            appendLine("[shell stopped]")
        }
        clearBtn.setOnClickListener {
            output.text = ""
        }

        root = col
        appendLine("[Shell tab] tap Start, then pkg a command.")
    }

    fun start() {
        if (!shell.running) shell.start()
    }

    fun stop() {
        // keep the shell alive while the tab is hidden; only the UI goes away
    }

    private fun send(cmd: String) {
        val trimmed = cmd.trim()
        if (trimmed.isEmpty()) return
        appendLine("$ $trimmed")
        if (!shell.running) {
            if (!shell.start()) return
        }
        shell.exec(trimmed)
        input.setText("")
    }

    private fun appendLine(line: String) {
        LogStore.log(Channel.SHELL, line)
        if (output.length() > 120_000) {
            output.text = output.text.takeLast(60_000)
        }
        output.append(if (output.text.isEmpty()) line else "\n$line")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
}
