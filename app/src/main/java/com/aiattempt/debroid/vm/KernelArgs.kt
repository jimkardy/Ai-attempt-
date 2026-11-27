package com.aiattempt.debroid.vm

import android.content.Context

/**
 * Extra User-Mode Linux parameters, typed in the Debug tab and appended to every
 * launch after the defaults (so they win). This exists so a boot problem can be
 * tried against `seccomp=auto`, `nocancel`, `nosysemu`, `con0=null,fd:1` and so
 * on without shipping a new APK for each attempt. Empty by default.
 */
object KernelArgs {

    private const val PREFS = "debroid-debug"
    private const val KEY = "extra-kernel-args"

    fun extra(ctx: Context): List<String> = runCatching {
        prefs(ctx).getString(KEY, "").orEmpty()
            .split(' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }.getOrDefault(emptyList())

    fun store(ctx: Context, value: String) {
        runCatching { prefs(ctx).edit().putString(KEY, value.trim()).apply() }
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
