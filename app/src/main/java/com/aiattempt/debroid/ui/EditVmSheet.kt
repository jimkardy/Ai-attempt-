package com.aiattempt.debroid.ui

import android.graphics.Typeface
import android.text.InputType
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
import com.aiattempt.debroid.sys.StorageReclaim
import com.aiattempt.debroid.vm.PortForward
import com.aiattempt.debroid.vm.VmInstance
import com.aiattempt.debroid.vm.VmManager
import com.aiattempt.debroid.vm.VmStore
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Edit sheet for an existing VM: name, cores, RAM, disk and port forwards, all
 * bounded by the same device budget as the create form. Resource changes are
 * stored immediately and take effect the next time the VM boots; the disk can
 * only grow, because shrinking a filesystem that already holds a guest's files
 * would destroy them.
 *
 * It also carries the **Reclaim Android storage** action (`fstrim -v /`), which
 * is Android's own way of releasing the blocks of deleted files back to the
 * device. See [StorageReclaim] for what a non-rooted app can and cannot do.
 */
class EditVmSheet(
    private val activity: AppCompatActivity,
    private val vm: VmInstance,
    private val onSaved: () -> Unit
) {

    private val budget = VmManager.budget(activity)

    fun show() {
        runCatching { buildSheet() }.onFailure {
            Toast.makeText(
                activity,
                "Could not open the editor: ${it.message ?: it.javaClass.simpleName}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun buildSheet() {
        val sheet = BottomSheetDialog(activity)
        val col = activity.column().apply {
            setPadding(activity.dpi(20), activity.dpi(12), activity.dpi(20), activity.dpi(24))
        }

        col.addView(
            TextView(activity).apply {
                text = "Edit ${vm.name}"
                textSize = 22f
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(activity.color(R.color.on_surface))
            }
        )
        col.addView(
            TextView(activity).apply {
                text = "Changes are saved now and used the next time this VM boots."
                textSize = 12.5f
                setTextColor(activity.color(R.color.on_surface_2))
                setPadding(0, 0, 0, activity.dpi(12))
            }
        )

        // ---- name ---------------------------------------------------------
        val nameField = field("VM name", InputType.TYPE_CLASS_TEXT).apply {
            setText(vm.name)
        }
        col.addView(nameField, matchWrap(top = activity.dpi(4)))

        // ---- cores --------------------------------------------------------
        val coresMax = budget.cores
        var cores = vm.cores.coerceIn(1, coresMax)
        val coresValue = valueText("$cores")
        val minus = activity.tonalButton("−")
        val plus = activity.tonalButton("+")
        fun updateCores() {
            coresValue.text = "$cores"
            minus.isEnabled = cores > 1
            plus.isEnabled = cores < coresMax
        }
        minus.setOnClickListener {
            cores = (cores - 1).coerceAtLeast(1)
            updateCores()
        }
        plus.setOnClickListener {
            cores = (cores + 1).coerceAtMost(coresMax)
            updateCores()
        }
        updateCores()
        col.addView(rowLabel("CPU cores", coresValue))
        col.addView(stepperRow(minus, coresValue, plus))
        col.addView(
            captionText(
                "$coresMax available on this device · UML currently schedules one guest CPU, " +
                    "so this is a reservation, not a speed-up"
            )
        )

        // ---- RAM ----------------------------------------------------------
        val ramMax = budget.ramMaxMb.coerceAtLeast(VmManager.MIN_RAM_MB)
        var ram = vm.ramMb.coerceIn(VmManager.MIN_RAM_MB, ramMax)
        val ramValue = valueText(formatMb(ram))
        col.addView(rowLabel("RAM", ramValue))
        if (ramMax > VmManager.MIN_RAM_MB) {
            col.addView(
                Slider(activity).apply {
                    valueFrom = VmManager.MIN_RAM_MB.toFloat()
                    valueTo = ramMax.toFloat()
                    stepSize = VmManager.RAM_STEP_MB.toFloat()
                    value = ram.toFloat()
                    addOnChangeListener { _, value, _ ->
                        ram = value.toInt()
                        ramValue.text = formatMb(ram)
                    }
                },
                matchWrap(top = activity.dpi(2))
            )
        }
        col.addView(
            captionText(
                "${formatMb(ramMax)} is the maximum here · ${formatMb(budget.ramFreeMb)} free of " +
                    "${formatMb(budget.ramTotalMb)}"
            )
        )

        // ---- disk ---------------------------------------------------------
        val currentDiskMb = runCatching {
            (VmStore.diskImage(activity, vm.id).length() / (1024 * 1024)).toInt()
        }.getOrDefault(0)
        val diskMin = maxOf(VmManager.MIN_DISK_MB, currentDiskMb)
        val diskMax = maxOf(budget.diskMaxMb, diskMin)
        var disk = vm.diskMb.coerceIn(diskMin, diskMax)
        val diskValue = valueText(VmInstance.formatDisk(disk))
        col.addView(rowLabel("Disk", diskValue))
        if (diskMax > diskMin) {
            col.addView(
                Slider(activity).apply {
                    valueFrom = diskMin.toFloat()
                    valueTo = diskMax.toFloat()
                    stepSize = VmManager.DISK_STEP_MB.toFloat()
                    value = disk.toFloat().coerceIn(diskMin.toFloat(), diskMax.toFloat())
                    addOnChangeListener { _, value, _ ->
                        disk = value.toInt()
                        diskValue.text = VmInstance.formatDisk(disk)
                    }
                },
                matchWrap(top = activity.dpi(2))
            )
        }
        col.addView(
            captionText(
                if (diskMin > VmManager.MIN_DISK_MB)
                    "the disk can only grow: it already holds this VM's filesystem " +
                        "(${VmInstance.formatDisk(diskMin)})"
                else "${VmInstance.formatDisk(diskMax)} is the maximum here · ${budget.diskFreeMb / 1024} GB free"
            )
        )

        // ---- port forwards -------------------------------------------------
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
            row.addView(
                activity.tonalButton("✕").apply {
                    setOnClickListener {
                        portContainer.removeView(row)
                        portRows.remove(hostField to guestField)
                    }
                },
                LinearLayout.LayoutParams(activity.dpi(48), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(activity.dpi(6), 0, 0, 0)
                }
            )
            portContainer.addView(row, matchWrap(top = activity.dpi(4)))
            portRows.add(hostField to guestField)
        }
        vm.ports.forEach { addPortRow(it.host.toString(), it.guest.toString()) }
        col.addView(portContainer)
        col.addView(
            activity.tonalButton("Add port forward").apply { setOnClickListener { addPortRow() } },
            matchWrap(top = activity.dpi(8))
        )

        // ---- storage reclaim ----------------------------------------------
        col.addView(activity.divider())
        col.addView(
            TextView(activity).apply {
                text = "ANDROID STORAGE"
                textSize = 11f
                letterSpacing = 0.08f
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(activity.color(R.color.on_surface_2))
                setPadding(0, 0, 0, activity.dpi(6))
            }
        )
        val reclaimOutput = activity.monoText("Not run yet.", 10.5f).apply {
            setBackgroundColor(activity.color(R.color.bg))
            setPadding(activity.dpi(12), activity.dpi(10), activity.dpi(12), activity.dpi(10))
        }
        val reclaimButton = activity.tonalButton("Reclaim Android storage").apply {
            setOnClickListener {
                isEnabled = false
                setText("Reclaiming…")
                reclaimOutput.text = "Running fstrim -v / and clearing caches…"
                activity.lifecycleScope.launch {
                    val text = withContext(Dispatchers.IO) {
                        runCatching { StorageReclaim.run(activity) }
                            .getOrElse { "reclaim failed: ${it.javaClass.simpleName}: ${it.message}" }
                    }
                    reclaimOutput.text = text
                    isEnabled = true
                    setText("Reclaim Android storage")
                    onSaved()
                }
            }
        }
        col.addView(reclaimButton, matchWrap(top = activity.dpi(2)))
        col.addView(
            captionText(
                "Runs fstrim -v / (the kernel's discard of deleted blocks) and clears Debroid's own " +
                    "caches. Android only allows fstrim for root, so on a normal phone it reports " +
                    "exactly what was refused instead of pretending."
            )
        )
        col.addView(
            ScrollView(activity).apply { addView(reclaimOutput) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dpi(150)).apply {
                topMargin = activity.dpi(6)
            }
        )

        // ---- actions -------------------------------------------------------
        col.addView(activity.divider())
        val actions = activity.row()
        actions.addView(
            activity.tonalButton("Cancel").apply { setOnClickListener { sheet.dismiss() } },
            LinearLayout.LayoutParams(0, activity.dpi(48), 1f).apply { setMargins(0, 0, activity.dpi(6), 0) }
        )
        actions.addView(
            activity.primaryButton("Save").apply {
                setOnClickListener {
                    val ports = portRows.mapNotNull { (h, g) ->
                        val host = h.text.toString().toIntOrNull() ?: return@mapNotNull null
                        val guest = g.text.toString().toIntOrNull() ?: return@mapNotNull null
                        if (host in 1024..65535 && guest in 1..65535) PortForward(host, guest) else null
                    }
                    VmManager.update(
                        ctx = activity,
                        id = vm.id,
                        name = nameField.text.toString(),
                        cores = cores,
                        ramMb = ram,
                        diskMb = disk,
                        ports = ports
                    ).onSuccess {
                        sheet.dismiss()
                        onSaved()
                        Toast.makeText(activity, "${it.name} updated", Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(activity, it.message ?: "Could not save the VM", Toast.LENGTH_LONG).show()
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
        background = android.graphics.drawable.GradientDrawable().apply {
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
        (value.parent as? ViewGroup)?.removeView(value)
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
