package com.aiattempt.debroid

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.aiattempt.debroid.log.Channel
import com.aiattempt.debroid.log.LogStore
import com.aiattempt.debroid.report.Report
import com.aiattempt.debroid.ui.DebugScreen
import com.aiattempt.debroid.ui.TerminalScreen
import com.aiattempt.debroid.ui.VmScreen
import com.aiattempt.debroid.ui.color
import com.aiattempt.debroid.ui.column
import com.aiattempt.debroid.ui.dpi
import com.aiattempt.debroid.vm.VmManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.appbar.MaterialToolbar

/**
 * Shell of the app: a slim toolbar, the active tab, and the bottom bar with the
 * three destinations (VM · Terminal · Debug). VM is the landing page.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var container: FrameLayout
    private lateinit var nav: BottomNavigationView

    private var vmScreen: VmScreen? = null
    private var terminalScreen: TerminalScreen? = null
    private var debugScreen: DebugScreen? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = column()
        root.setBackgroundColor(color(R.color.bg))

        val toolbar = MaterialToolbar(this).apply {
            setTitle("Debroid")
            setSubtitle("Debian via UML · arm64")
            setTitleTextColor(this@MainActivity.color(R.color.on_surface))
            setSubtitleTextColor(this@MainActivity.color(R.color.on_surface_3))
            setBackgroundColor(this@MainActivity.color(R.color.bg))
            setPadding(0, dpi(4), 0, 0)
        }
        root.addView(toolbar)

        container = FrameLayout(this)
        root.addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        nav = BottomNavigationView(this).apply {
            labelVisibilityMode = NavigationBarView.LABEL_VISIBILITY_LABELED
            setBackgroundColor(this@MainActivity.color(R.color.surface))
            menu.add(0, TAB_VM, 0, getString(R.string.tab_vm)).setIcon(R.drawable.ic_vm)
            menu.add(0, TAB_TERMINAL, 1, getString(R.string.tab_terminal)).setIcon(R.drawable.ic_terminal)
            menu.add(0, TAB_DEBUG, 2, getString(R.string.tab_debug)).setIcon(R.drawable.ic_debug)
            setOnItemSelectedListener { item ->
                runCatching { show(item.itemId) }
                true
            }
        }
        root.addView(nav)
        setContentView(root)

        VmManager.init(this)
        VmManager.onChanged = { runOnUiThread { refreshCurrent() } }

        nav.selectedItemId = TAB_VM
        LogStore.log(Channel.APP, "MainActivity created")
        Handler(Looper.getMainLooper()).post { runCatching { maybeShowCrashDialog() } }
    }

    override fun onDestroy() {
        super.onDestroy()
        VmManager.onChanged = null
    }

    private fun show(tab: Int) {
        val screen: View = when (tab) {
            TAB_TERMINAL -> {
                val existing = terminalScreen
                if (existing == null) {
                    val created = TerminalScreen(this, onOpenVmTab = { nav.selectedItemId = TAB_VM })
                    terminalScreen = created
                    created.root
                } else {
                    existing.root
                }
            }
            TAB_DEBUG -> {
                val existing = debugScreen
                if (existing == null) {
                    val created = DebugScreen(this)
                    debugScreen = created
                    created.root
                } else {
                    existing.root
                }
            }
            else -> {
                val existing = vmScreen
                if (existing == null) {
                    val created = VmScreen(this, onOpenTerminal = { nav.selectedItemId = TAB_TERMINAL })
                    vmScreen = created
                    created.root
                } else {
                    existing.root
                }
            }
        }
        container.removeAllViews()
        container.addView(
            screen,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        refreshCurrent()
        LogStore.log(Channel.APP, "tab shown: ${nav.menu.findItem(tab)?.title}")
    }

    /** Called when a VM starts: jump to its terminal. */
    fun openTerminal() {
        nav.selectedItemId = TAB_TERMINAL
    }

    private fun refreshCurrent() {
        runCatching { vmScreen?.refresh() }
        runCatching { terminalScreen?.refresh() }
        runCatching { debugScreen?.refresh() }
    }

    private fun maybeShowCrashDialog() {
        val pending = LogStore.pendingCrash() ?: return
        val text = pending.takeLast(3000)
        MaterialAlertDialogBuilder(this)
            .setTitle("Last session crashed")
            .setMessage(text)
            .setPositiveButton("Copy") { _, _ ->
                if (Report.copy(this, pending)) {
                    android.widget.Toast.makeText(this, "Crash copied — paste it into the issue", android.widget.Toast.LENGTH_LONG).show()
                }
                LogStore.acknowledgeCrash()
            }
            .setNeutralButton("Open Debug tab") { _, _ ->
                LogStore.acknowledgeCrash()
                nav.selectedItemId = TAB_DEBUG
            }
            .setNegativeButton("Dismiss") { _, _ -> LogStore.acknowledgeCrash() }
            .show()
    }

    companion object {
        private const val TAB_VM = 1
        private const val TAB_TERMINAL = 2
        private const val TAB_DEBUG = 3
    }
}
