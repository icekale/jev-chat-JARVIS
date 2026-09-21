package com.jev.probe.shizuku

import android.content.Context
import androidx.annotation.Keep
import java.util.concurrent.TimeUnit

/**
 * Runs inside a Shizuku-started process (shell uid). Only the two unlock
 * operations this app needs — no arbitrary shell.
 */
class JevShellService : IJevShellService.Stub {

    constructor()

    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context)

    override fun destroy() {
        System.exit(0)
    }

    override fun allowRestrictedSettings(packageName: String): String {
        if (packageName != PKG) return "denied: package"
        return exec(arrayOf("cmd", "appops", "set", PKG, "ACCESS_RESTRICTED_SETTINGS", "allow"))
    }

    override fun putSecureSetting(name: String, value: String): String {
        if (name !in SETTINGS) return "denied: name"
        if (value.any { it == '\n' || it == ';' || it == '`' || it == '$' || it == '|' }) {
            return "denied: value"
        }
        if (name == "accessibility_enabled" && value != "1") return "denied: value"
        if (name == "enabled_accessibility_services" && COMPONENTS.none { value.contains(it) }) {
            return "denied: component"
        }
        return exec(arrayOf("settings", "put", "secure", name, value))
    }

    private fun exec(cmd: Array<String>): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        val finished = p.waitFor(8, TimeUnit.SECONDS)
        if (!finished) {
            p.destroyForcibly()
            return "exit=timeout"
        }
        return "exit=${p.exitValue()}\n$out".trim()
    }

    companion object {
        private const val PKG = "com.jev.probe"
        private val SETTINGS = setOf("enabled_accessibility_services", "accessibility_enabled")
        private val COMPONENTS = setOf(
            "com.jev.probe/com.jev.probe.capture.JevAccessibilityService",
            "com.jev.probe/com.google.android.accessibility.selecttospeak.SelectToSpeakService"
        )
    }
}
