package com.jev.probe.core

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * ColorOS / stock ROMs often refuse the disguised Google class name.
 * Keep it enabled only on MIUI / HyperOS where WeChat node hiding is the
 * original reason it exists. The plain [JevAccessibilityService] stays on
 * everywhere.
 */
object A11yComponents {
    private const val DISGUISED =
        "com.google.android.accessibility.selecttospeak.SelectToSpeakService"

    fun apply(ctx: Context) {
        val pm = ctx.packageManager
        val disguised = ComponentName(ctx, DISGUISED)
        val state = if (OemSettings.isHyperOs())
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        runCatching {
            pm.setComponentEnabledSetting(disguised, state, PackageManager.DONT_KILL_APP)
        }
    }
}
