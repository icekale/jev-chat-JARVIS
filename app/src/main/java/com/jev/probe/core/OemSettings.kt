package com.jev.probe.core

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/**
 * OEM-aware jumps into system settings. ColorOS / OxygenOS often swallow
 * [Settings.ACTION_ACCESSIBILITY_SETTINGS] from third-party apps and lock
 * sideloaded accessibility toggles behind "restricted settings".
 */
object OemSettings {

    const val PLAIN_COMPONENT =
        "com.jev.probe/com.jev.probe.capture.JevAccessibilityService"
    const val DISGUISED_COMPONENT =
        "com.jev.probe/com.google.android.accessibility.selecttospeak.SelectToSpeakService"

    fun isA11yEnabled(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(PLAIN_COMPONENT) || enabled.contains(DISGUISED_COMPONENT)
    }

    fun mergeA11yComponent(existing: String, component: String): String {
        val parts = existing.split(':').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.any { it == component }) return parts.joinToString(":")
        return (parts + component).joinToString(":")
    }

    const val WECHAT_PKG = "com.tencent.mm"

    fun isColorOs(): Boolean {
        val fingerprint = listOf(
            Build.MANUFACTURER, Build.BRAND, Build.DISPLAY, Build.FINGERPRINT
        ).joinToString(" ").lowercase()
        if (listOf("oppo", "oneplus", "realme", "coloros", "oxygen", "oplus")
                .any { fingerprint.contains(it) }
        ) return true
        return listOf(
            "ro.build.version.oplusrom",
            "ro.build.version.opporom",
            "ro.oplus.theme.version",
            "ro.coloros.version"
        ).any { sysProp(it).isNotBlank() }
    }

    fun isHyperOs(): Boolean {
        val fingerprint = listOf(
            Build.MANUFACTURER, Build.BRAND, Build.DISPLAY
        ).joinToString(" ").lowercase()
        if (listOf("xiaomi", "redmi", "poco", "hyperos", "miui").any { fingerprint.contains(it) })
            return true
        return listOf("ro.mi.os.version.name", "ro.miui.ui.version.name")
            .any { sysProp(it).isNotBlank() }
    }

    fun needsRestrictedUnlock(): Boolean =
        isColorOs() || isHyperOs() || Build.VERSION.SDK_INT >= 33

    fun openAppDetails(activity: Activity) {
        val uri = Uri.parse("package:${activity.packageName}")
        val intents = listOf(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(uri)
        )
        if (!intents.any { tryStart(activity, it) }) {
            Toast.makeText(activity, "打不开应用详情，请到系统设置里搜索「Jev助手」", Toast.LENGTH_LONG).show()
        }
    }

    fun openAccessibility(activity: Activity) {
        val highlight = PLAIN_COMPONENT
        val candidates = ArrayList<Intent>()
        candidates += accessibilityIntent(highlight)
        candidates += componentIntent(
            "com.android.settings",
            "com.android.settings.Settings\$AccessibilitySettingsActivity"
        )
        candidates += componentIntent(
            "com.android.settings",
            "com.oplus.settings.feature.accessibility.OplusAccessibilitySettingsActivity"
        )
        candidates += componentIntent(
            "com.oplus.safecenter",
            "com.oplus.safecenter.permission.PermissionTopActivity"
        )
        candidates += componentIntent(
            "com.coloros.safecenter",
            "com.coloros.privacypermissionsentry.PermissionTopActivity"
        )
        candidates += Intent(Settings.ACTION_SETTINGS)
        if (!candidates.any { tryStart(activity, it) }) {
            Toast.makeText(
                activity,
                "系统拦截了跳转。请手动：设置 → 其他设置 → 无障碍 → 已下载的应用",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun showA11yGuide(activity: Activity) {
        val color = isColorOs()
        val title = if (color) "ColorOS 开无障碍" else "开启无障碍"
        val message = if (color) colorOsGuide() else genericGuide()
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("打开应用详情") { _, _ -> openAppDetails(activity) }
            .setNeutralButton("打开无障碍") { _, _ -> openAccessibility(activity) }
            .setNegativeButton("复制步骤") { _, _ ->
                copy(activity, message)
                Toast.makeText(activity, "已复制", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun colorOsGuide(): String = """
        ColorOS 会拦截旁加载应用的无障碍，弹窗「系统已拒绝向此应用授予访问权限」是系统锁，不是 App 坏了。不用越狱。

        先看弹窗：点「了解如何授予访问权限」，按系统提示打开「允许受限制的设置」。

        若没有这项，按这个顺序重来：
        1. 卸载 Jev助手
        2. 用手机自带「文件管理」（不要用浏览器/网盘直接打开）找到 APK，让系统安装器扫毒并安装
        3. 装完按「完成」，先不要点「打开」
        4. 设置 → 其他设置 → 无障碍 → 已下载的应用 → 打开「Jev助手」
        5. 开发者选项里若有「禁止权限监控」，打开后再试第 4 步

        桌面长按图标 → 应用信息 → 右上角 ⋮ 里也可能有「允许受限制的设置」。
    """.trimIndent()

    private fun genericGuide(): String = """
        旁加载安装的应用，系统会锁住无障碍开关。

        1. 点「打开应用详情」→ 右上角 ⋮ → 允许受限设置
        2. 点「打开无障碍」→ 已下载的应用 / 已安装的服务 → 打开「Jev助手」
        3. 小米还请打开自启动和省电无限制
    """.trimIndent()

    private fun accessibilityIntent(component: String): Intent {
        val args = Bundle().apply { putString(":settings:fragment_args_key", component) }
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            putExtra(":settings:fragment_args_key", component)
            putExtra(":settings:show_fragment_args", args)
        }
    }

    private fun componentIntent(pkg: String, cls: String): Intent =
        Intent().setComponent(ComponentName(pkg, cls))

    private fun tryStart(activity: Activity, intent: Intent): Boolean {
        return runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(activity.packageManager) == null &&
                intent.component == null
            ) return false
            activity.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun copy(ctx: Context, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("jev_a11y_guide", text))
    }

    private fun sysProp(key: String): String = runCatching {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java, String::class.java)
            .invoke(null, key, "") as String
    }.getOrDefault("")
}
