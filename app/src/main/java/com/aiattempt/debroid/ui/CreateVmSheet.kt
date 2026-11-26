package com.aiattempt.debroid.ui

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import com.aiattempt.debroid.R
import com.aiattempt.debroid.vm.PortForward
import com.aiattempt.debroid.vm.VmInstance
import com.aiattempt.debroid.vm.VmManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.slider.Slider

/**
 * Create-VM form. Every resource is bounded by what the phone actually has:
 * cores by the CPU count, RAM by free RAM (minus an Android/app reserve), disk by
 * free storage (minus a reserve). There is no free-text size field, so nobody can
 * type a value that starves the phone.
 */
class CreateVmSheet(
    private val activity: AppCompatActivity,
    private val onCreated: () -> Unit
) {

    private val budget = VmManager.budget(activity)

    fun show() {
        val sheet = BottomSheetDialog(activity)
        val col = activity.column().apply {
            setPadding(activity.dpi(20), activity.dpi(12), activity.dpi(20), activity.dpi(24))
        }

        col.addView(
            TextView(activity).apply {
                text = "Create VM"
                textSize = 22f
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(activity.color(R.color.on_surface))
                setPadding(0, 0, 0, activity.dpi(2))
            }
        )
        col.addView(
            TextView(activity).apply {
                text = "Debian arm64 on a sparse disk. Limits below are read from this device."
                textSize = 12.5f
                setTextColor(activity.color(R.color.on_surface_2))
                setPadding(0, 0, 0, activity.dpi(12))
            }
        )

        // ---- name ---------------------------------------------------------
        val nameField = field("VM name", InputType.TYPE_CLASS_TEXT).apply {
            setText("Debian ${VmManager.list(activity).size + 1}")
        }
        col.addView(nameField, matchWrap(top = activity.dpi(4)))

        // ---- cores --------------------------------------------------------
        var cores = minOf(2, budget.cores)
        val coresValue = valueText("$cores")
        val coresCaption = captionText("${budget.cores} available on this device")
        val minus = activity.tonalButton("−")
        val plus = activity.tonalButton("+")
        fun updateCores() {
            coresValue.text = "$cores"
            minus.isEnabled = cores > 1
            plus.isEnabled = cores < budget.cores
        }
        minus.setOnClickListener {
            cores = (cores - 1).coerceAtLeast(1)
            updateCores()
        }
        plus.setOnClickListener {
            cores = (cores + 1).coerceAtMost(budget.cores)
            updateCores()
        }
        updateCores()
        col.addView(rowLabel("CPU cores", coresValue))
        col.addView(stepperRow(minus, coresValue, plus))
        col.addView(coresCaption)

        // ---- RAM ----------------------------------------------------------
        val ramMax = budget.ramMaxMb
        val ramStart = minOf(512, ramMax).coerceAtLeast(VmManager.MIN_RAM_MB)
        val ramValue = valueText(formatMb(ramStart))
        val ramCaption = captionText(
            "${formatMb(ramMax)} is the maximum here · ${formatMb(budget.ramFreeMb)} free of ${formatMb(budget.ramTotalMb)}"
        )
        col.addView(rowLabel("RAM", ramValue))
        if (ramMax > VmManager.MIN_RAM_MB) {
            val slider = Slider(activity).apply {
                valueFrom = VmManager.MIN_RAM_MB.toFloat()
                valueTo = ramMax.toFloat()
                stepSize = VmManager.RAM_STEP_MB.toFloat()
                value = ramStart.toFloat()
                addOnChangeListener { _, value, _ -> ramValue.text = formatMb(value.toInt()) }
            }
            col.addView(slider, matchWrap(top = activity.dpi(2)))
        } else {
            col.addView(
                TextView(activity).apply {
                    text = "Only ${formatMb(budget.ramFreeMb)} free RAM — this device cannot host a VM right now."
                    textSize = 12f
                    setTextColor(activity.color(R.color.err))
                    setPadding(0, 0, 0, activity.dpi(8))
                }
            )
        }
        col.addView(ramCaption)

        // ---- disk ---------------------------------------------------------
        val diskMax = budget.diskMaxMb
        val diskStart = minOf(4096, diskMax).coerceAtLeast(VmManager.MIN_DISK_MB)
        val diskValue = valueText(VmInstance.formatDisk(diskStart))
        val diskCaption = captionText(
            "${VmInstance.formatDisk(diskMax)} is the maximum here · ${budget.diskFreeMb / 1024} GB free on the device"
        )
        col.addView(rowLabel("Disk", diskValue))
        if (diskMax > VmManager.MIN_DISK_MB) {
            val slider = Slider(activity).apply {
                valueFrom = VmManager.MIN_DISK_MB.toFloat()
                valueTo = diskMax.toFloat()
                stepSize = VmManager.DISK_STEP_MB.toFloat()
                value = diskStart.toFloat()
                addOnChangeListener { _, value, _ -> diskValue.text = VmInstance.formatDisk(value.toInt()) }
            }
            col.addView(slider, matchWrap(top = activity.dpi(2)))
        }
        col.addView(diskCaption)

        // ---- port forwards ------------------------------------------------
        col.addView(
            TextView(activity).apply {
                text = "PORT FORWARDS"
                textSize = 11f
                letterSpacing = 0.08f
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(activity.color(R.color.on_surface_2))
                setPadding(0, activity.dpi(14), 0, activity.dpi(6))
            }
        )
        val portContainer = activity.column()
        val portRows = ArrayList<Pair<EditText, EditText>>()
        fun addPortRow(host: String = "", guest: String = "") {
            if (portRows.size >= 8) return
            val hostField = field("host", InputType.TYPE_CLASS_NUMBER).apply {
                setText(host); textSize = 13f
            }
            val guestField = field("guest", InputType.TYPE_CLASS_NUMBER).apply {
                setText(guest); textSize = 13f
            }
            val row = activity.row()
            row.addView(hostField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(
                TextView(activity).apply {
                    text = "→"
                    setTextColor(activity.color(R.color.on_surface_3))
                    setPadding(activity.dpi(8), 0, activity.dpi(8), 0)
                }
            )
            row.addView(guestField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val remove = activity.tonalButton("✕").apply {
                setOnClickListener {
                    portContainer.removeView(row)
                    portRows.remove(hostField to guestField)
                }
            }
            row.addView(remove, LinearLayout.LayoutParams(activity.dpi(48), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(activity.dpi(6), 0, 0, 0)
            })
            portContainer.addView(row, matchWrap(top = activity.dpi(4)))
            portRows.add(hostField to guestField)
        }
        col.addView(portContainer)
        col.addView(
            activity.tonalButton("Add port forward").apply {
                setOnClickListener { addPortRow() }
            },
            matchWrap(top = activity.dpi(8))
        )

        // ---- budget summary + actions -------------------------------------
        col.addView(activity.divider())
        col.addView(
            TextView(activity).apply {
                text = VmManager.budgetLine(activity)
                textSize = 11.5f
                setTextColor(activity.color(R.color.on_surface_3))
                setPadding(0, 0, 0, activity.dpi(10))
            }
        )
        if (budget.reason != null) {
            col.addView(
                TextView(activity).apply {
                    text = budget.reason!!
                    textSize = 12f
                    setTextColor(activity.color(R.color.err))
                    setPadding(0, 0, 0, activity.dpi(10))
                }
            )
        }

        val actions = activity.row()
        actions.addView(
            activity.tonalButton("Cancel").apply { setOnClickListener { sheet.dismiss() } },
            LinearLayout.LayoutParams(0, activity.dpi(48), 1f).apply { setMargins(0, 0, activity.dpi(6), 0) }
        )
        actions.addView(
            activity.primaryButton("Create VM").apply {
                isEnabled = budget.canCreate
                alpha = if (budget.canCreate) 1f else 0.4f
                setOnClickListener {
                    val ram = ramStart.coerceAtMost(ramMax)
                    val ports = portRows.mapNotNull { (h, g) ->
                        val host = h.text.toString().toIntOrNull() ?: return@mapNotNull null
                        val guest = g.text.toString().toIntOrNull() ?: return@mapNotNull null
                        if (host in 1024..65535 && guest in 1..65535) PortForward(host, guest) else null
                    }
                    val result = VmManager.create(
                        ctx = activity,
                        name = nameField.text.toString(),
                        cores = cores,
                        ramMb = ram,
                        diskMb = diskStart,
                        ports = ports
                    )
                    result.onSuccess {
                        sheet.dismiss()
                        onCreated()
                        Toast.makeText(activity, "${it.name} created — press Start", Toast.LENGTH_LONG).show()
                    }
                    result.onFailure {
                        Toast.makeText(activity, it.message ?: "Could not create the VM", Toast.LENGTH_LONG).show()
                    }
                }
            },
            LinearLayout.LayoutParams(0, activity.dpi(48), 1f).apply { setMargins(activity.dpi(6), 0, 0, 0) }
        )
        col.addView(actions)

        val scroll = ScrollView(activity).apply {
            addView(col)
            clipToPadding = false
        }
        sheet.setContentView(scroll)
        sheet.show()
    }

    // ---- small builders ---------------------------------------------------

    private fun field(hint: String, inputType: Int): EditText = EditText(activity).apply {
        this.hint = hint
        this.inputType = inputType
        setSingleLine()
        textSize = 14f
        setTextColor(activity.color(R.color.on_surface))
        setHintTextColor(activity.color(R.color.on_surface_3))
        setPadding(activity.dpi(14), activity.dpi(11), activity.dpi(14), activity.dpi(11))
        background = GradientDrawable().apply {
            cornerRadius = activity.dpi(12).toFloat()
            setColor(activity.color(R.color.surface_2))
            setStroke(activity.dpi(1), activity.color(R.color.outline))
        }
    }

    private fun valueText(value: String): TextView = TextView(activity).apply {
        text = value
        textSize = 13f
        setTypeface(Typeface.MONOSPACE)
        setTextColor(activity.color(R.color.on_surface))
    }

    private fun captionText(value: String): TextView = TextView(activity).apply {
        text = value
        textSize = 11.5f
        setTextColor(activity.color(R.color.on_surface_3))
        setPadding(0, activity.dpi(2), 0, activity.dpi(6))
    }

    private fun rowLabel(label: String, value: TextView): View = activity.row().apply {
        setPadding(0, activity.dpi(12), 0, 0)
        addView(
            TextView(activity).apply {
                text = label
                textSize = 13f
                setTextColor(activity.color(R.color.on_surface_2))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        addView(value)
    }

    private fun stepperRow(minus: View, value: TextView, plus: View): View = activity.row().apply {
        setPadding(0, activity.dpi(6), 0, 0)
        value.gravity = Gravity.CENTER
        value.textSize = 16f
        addView(minus, LinearLayout.LayoutParams(activity.dpi(56), activity.dpi(44)))
        addView(value, LinearLayout.LayoutParams(activity.dpi(56), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(plus, LinearLayout.LayoutParams(activity.dpi(56), activity.dpi(44)))
    }

    private fun matchWrap(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = top }

    private fun formatMb(mb: Int): String =
        if (mb >= 1024) String.format("%.1f GB", mb / 1024.0) else "$mb MB"
}
