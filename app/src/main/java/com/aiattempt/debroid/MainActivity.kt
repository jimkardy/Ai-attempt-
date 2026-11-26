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
import com.aiattempt.debroid.ui.column
import com.aiattempt.debroid.ui.dpi
import com.aiattempt.debroid.ui.monoText
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationBarView

class MainActivity : AppCompatActivity() {

    private lateinit var container: FrameLayout
    private var terminal: TerminalScreen? = null
    private var vm: VmScreen? = null
    private var debug: DebugScreen? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container = FrameLayout(this)
        root.addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val nav = BottomNavigationView(this).apply {
            labelVisibilityMode = NavigationBarView.LABEL_VISIBILITY_LABELED
            menu.add(0, TAB_TERMINAL, 0, getString(R.string.tab_terminal)).setIcon(R.drawable.ic_terminal)
            menu.add(0, TAB_VM, 1, getString(R.string.tab_vm)).setIcon(R.drawable.ic_vm)
            menu.add(0, TAB_DEBUG, 2, getString(R.string.tab_debug)).setIcon(R.drawable.ic_debug)
            setOnItemSelectedListener { item ->
                runCatching { show(item.itemId) }
                true
            }
        }
        root.addView(nav)
        setContentView(root)

        // v1 is a diagnostics build: open on Debug.
        nav.selectedItemId = TAB_DEBUG
        LogStore.log(Channel.APP, "MainActivity created")

        Handler(Looper.getMainLooper()).post { runCatching { maybeShowCrashDialog() } }
    }

    private fun show(tab: Int) {
        val screen: View = when (tab) {
            TAB_TERMINAL -> {
                if (terminal == null) terminal = TerminalScreen(this)
                terminal!!.let { it.start(); it.root }
            }
            TAB_VM -> {
                if (vm == null) vm = VmScreen(this)
                vm!!.let { it.start(); it.root }
            }
            else -> {
                if (debug == null) debug = DebugScreen(this)
                debug!!.let { it.start(); it.root }
            }
        }
        container.removeAllViews()
        container.addView(
            screen,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
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
            }
            .setNegativeButton("Dismiss") { _, _ ->
                LogStore.acknowledgeCrash()
            }
            .show()
    }

    companion object {
        private const val TAB_TERMINAL = 1
        private const val TAB_VM = 2
        private const val TAB_DEBUG = 3
    }
}
