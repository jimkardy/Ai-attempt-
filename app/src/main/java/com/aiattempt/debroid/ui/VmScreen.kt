package com.aiattempt.debroid.ui

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.aiattempt.debroid.R
import com.aiattempt.debroid.vm.VmInstance
import com.aiattempt.debroid.vm.VmManager
import com.aiattempt.debroid.vm.VmState
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * The VM tab: the landing page. Docker-style cards, each with start / stop /
 * restart / delete, plus the create flow. Capacity is hard-capped at
 * [VmManager.MAX_VMS] and only one VM can run at a time.
 */
class VmScreen(
    private val activity: AppCompatActivity,
    private val onOpenTerminal: () -> Unit
) {

    val root: View
    private val listColumn: LinearLayout = activity.column()
    private val capacityPill: TextView = activity.pill("0 of ${VmManager.MAX_VMS}")
    private val budgetText: TextView = activity.captionText("")
    private val emptyHint: TextView = activity.bodyText("", 13f)

    private var lastCount = -1

    init {
        val col = activity.column()

        val header = activity.row().apply { setPadding(activity.dpi(16), activity.dpi(6), activity.dpi(16), 0) }
        header.addView(
            activity.titleText("Virtual machines").apply { setPadding(0, activity.dpi(8), 0, activity.dpi(2)) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(capacityPill)
        col.addView(header)
        col.addView(budgetText)

        val scroll = ScrollView(activity).apply {
            addView(listColumn)
            clipToPadding = false
            setPadding(0, activity.dpi(4), 0, activity.dpi(16))
        }
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val footer = activity.column().apply {
            setPadding(activity.dpi(12), activity.dpi(6), activity.dpi(12), activity.dpi(12))
        }
        footer.addView(emptyHint)
        col.addView(footer)

        root = col
    }

    fun refresh() {
        if (!root.isAttachedToWindow) return
        runCatching { render() }
    }

    private fun render() {
        val vms = VmManager.list(activity)
        val budget = VmManager.budget(activity)

        capacityPill.text = "${vms.size} of ${VmManager.MAX_VMS}"
        budgetText.text = VmManager.budgetLine(activity)

        listColumn.removeAllViews()
        if (vms.isEmpty()) {
            listColumn.addView(emptyState(budget.reason))
        } else {
            vms.forEach { vm -> listColumn.addView(vmCard(vm)) }
        }

        emptyHint.text = when {
            budget.reason != null -> budget.reason
            vms.size >= VmManager.MAX_VMS -> "Capacity reached: delete a VM to create another one."
            else -> "Each VM is a full Debian arm64 userland on its own sparse disk."
        }
        lastCount = vms.size
    }

    private fun emptyState(reason: String?): View {
        val card = activity.card {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(activity.dpi(8), activity.dpi(24), activity.dpi(8), activity.dpi(24))
            addView(
                TextView(activity).apply {
                    text = "No VMs yet"
                    textSize = 22f
                    setTextColor(activity.color(R.color.on_surface))
                    gravity = Gravity.CENTER
                    setPadding(activity.dpi(12), 0, activity.dpi(12), activity.dpi(6))
                }
            )
            addView(
                TextView(activity).apply {
                    text = "Create a VM and Debroid sets up the disk, the resources and the terminal for it."
                    textSize = 13f
                    setTextColor(activity.color(R.color.on_surface_2))
                    gravity = Gravity.CENTER
                    setPadding(activity.dpi(20), 0, activity.dpi(20), activity.dpi(18))
                }
            )
            addView(
                activity.primaryButton("Create VM").apply {
                    isEnabled = reason == null
                    setOnClickListener { openCreateSheet() }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dpi(52)).apply {
                    setMargins(activity.dpi(24), 0, activity.dpi(24), if (reason == null) activity.dpi(4) else activity.dpi(8))
                }
            )
            if (reason != null) {
                addView(
                    TextView(activity).apply {
                        text = reason
                        textSize = 12f
                        setTextColor(activity.color(R.color.warn))
                        gravity = Gravity.CENTER
                        setPadding(activity.dpi(20), 0, activity.dpi(20), activity.dpi(8))
                    }
                )
            }
        }
        // Keep the empty state vertically centred in the scroll area.
        card.layoutParams = (card.layoutParams as LinearLayout.LayoutParams).apply {
            topMargin = activity.dpi(48)
        }
        return card
    }

    private fun vmCard(vm: VmInstance): View {
        val running = VmManager.isRunning(activity, vm.id)
        val stateColor = when (vm.state) {
            VmState.RUNNING -> activity.color(R.color.ok)
            VmState.STARTING -> activity.color(R.color.warn)
            VmState.ERROR -> activity.color(R.color.err)
            VmState.STOPPED -> activity.color(R.color.on_surface_3)
        }

        return activity.card {
            val titleRow = activity.row().apply { setPadding(activity.dpi(14), activity.dpi(12), activity.dpi(14), 0) }
            titleRow.addView(
                TextView(activity).apply {
                    text = vm.name
                    textSize = 17f
                    setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                    setTextColor(activity.color(R.color.on_surface))
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            titleRow.addView(activity.pill(vm.state.name, stateColor))
            addView(titleRow)

            addView(
                TextView(activity).apply {
                    text = vm.specLine
                    textSize = 12f
                    setTextColor(activity.color(R.color.on_surface_2))
                    setPadding(activity.dpi(14), activity.dpi(4), activity.dpi(14), 0)
                }
            )

            if (vm.ports.isNotEmpty()) {
                addView(
                    TextView(activity).apply {
                        text = vm.ports.joinToString("  ") { "host ${it.host} → ${it.guest}" }
                        textSize = 11.5f
                        setTextColor(activity.color(R.color.on_surface_3))
                        setPadding(activity.dpi(14), activity.dpi(4), activity.dpi(14), 0)
                    }
                )
            }

            if (running) {
                addView(
                    TextView(activity).apply {
                        text = "up ${formatUptime(vm.uptimeSeconds())} · console attached"
                        textSize = 11.5f
                        setTextColor(activity.color(R.color.ok))
                        setPadding(activity.dpi(14), activity.dpi(4), activity.dpi(14), 0)
                    }
                )
            }

            if (vm.message.isNotBlank() && vm.state != VmState.RUNNING) {
                addView(
                    TextView(activity).apply {
                        text = vm.message
                        textSize = 11.5f
                        setTextColor(
                            if (vm.state == VmState.ERROR) activity.color(R.color.err)
                            else activity.color(R.color.on_surface_3)
                        )
                        setPadding(activity.dpi(14), activity.dpi(6), activity.dpi(14), 0)
                    }
                )
            }

            val startStop = if (running) activity.tonalButton("Stop") else activity.primaryButton("Start")
            startStop.setOnClickListener {
                if (running) {
                    VmManager.stop(activity, vm.id)
                } else {
                    val result = VmManager.start(activity, vm.id)
                    result.onSuccess { onOpenTerminal() }
                    result.onFailure { toast(it.message ?: "Could not start the VM") }
                }
                refresh()
            }

            val restart = activity.tonalButton("Restart").apply {
                isEnabled = running
                setOnClickListener {
                    val result = VmManager.restart(activity, vm.id)
                    result.onSuccess { onOpenTerminal() }
                    result.onFailure { toast(it.message ?: "Could not restart the VM") }
                    refresh()
                }
            }

            val delete = activity.dangerButton("Delete").apply {
                setOnClickListener { confirmDelete(vm) }
            }

            addView(activity.actionRow(startStop, restart, delete))

            if (running) {
                addView(
                    activity.tonalButton("Open terminal").apply {
                        setOnClickListener { onOpenTerminal() }
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dpi(44)).apply {
                        setMargins(activity.dpi(14), 0, activity.dpi(14), activity.dpi(12))
                    }
                )
            }
        }
    }

    private fun confirmDelete(vm: VmInstance) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Delete ${vm.name}?")
            .setMessage("This removes the VM, its sparse disk image and its console log. It cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                VmManager.delete(activity, vm.id).onFailure { toast(it.message ?: "Delete failed") }
                refresh()
            }
            .show()
    }

    private fun openCreateSheet() {
        if (VmManager.list(activity).size >= VmManager.MAX_VMS) {
            toast("Delete a VM first: ${VmManager.MAX_VMS} of ${VmManager.MAX_VMS} used.")
            return
        }
        CreateVmSheet(activity) { refresh() }.show()
    }

    private fun toast(message: String) {
        runCatching { Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }
    }

    private fun formatUptime(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "${h}h ${m}m" else if (m > 0) "${m}m ${s}s" else "${s}s"
    }
}
