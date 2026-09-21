package com.jev.probe.shizuku

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.jev.probe.BuildConfig
import com.jev.probe.core.OemSettings
import rikka.shizuku.Shizuku
import java.lang.ref.WeakReference

/**
 * Uses user-authorized Shizuku (ADB/shell, not root) to grant this app
 * ACCESS_RESTRICTED_SETTINGS and enable our own accessibility service.
 */
object ShizukuUnlock {

    const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    private const val REQ = 4101
    private const val TIMEOUT_MS = 8000L

    private val main = Handler(Looper.getMainLooper())
    private var pending: ((String) -> Unit)? = null
    private var pendingActivity: WeakReference<Activity>? = null
    private var timeout: Runnable? = null

    enum class State { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, READY }

    fun state(activity: Activity): State {
        if (!installed(activity)) return State.NOT_INSTALLED
        return try {
            if (!Shizuku.pingBinder()) State.NOT_RUNNING
            else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)
                State.NO_PERMISSION
            else State.READY
        } catch (_: Throwable) {
            State.NOT_RUNNING
        }
    }

    fun statusLine(activity: Activity): String = when (state(activity)) {
        State.NOT_INSTALLED -> "未安装 Shizuku。点此安装（无线调试启动，不用越狱）"
        State.NOT_RUNNING -> "Shizuku 未运行。先打开 Shizuku 并启动"
        State.NO_PERMISSION -> "已安装。点此授权 Shizuku，再解锁无障碍"
        State.READY -> "已授权。点此解锁本应用的无障碍（仅操作 Jev助手）"
    }

    fun bindListeners() {
        Shizuku.addRequestPermissionResultListener(permListener)
    }

    fun unbindListeners() {
        Shizuku.removeRequestPermissionResultListener(permListener)
        clearTimeout()
        pending = null
        pendingActivity = null
    }

    private val permListener = Shizuku.OnRequestPermissionResultListener { code, grant ->
        if (code != REQ) return@OnRequestPermissionResultListener
        val cb = pending
        if (grant == PackageManager.PERMISSION_GRANTED) {
            val act = pendingActivity?.get()
            if (act != null) runUnlock(act, cb)
            else cb?.invoke("已授权，请再点一次「Shizuku 解锁」")
        } else {
            cb?.invoke("未授予 Shizuku 权限")
        }
    }

    fun start(activity: Activity, onDone: (String) -> Unit) {
        pending = onDone
        pendingActivity = WeakReference(activity)
        when (state(activity)) {
            State.NOT_INSTALLED -> {
                explainInstall(activity)
                onDone("请先安装并启动 Shizuku")
            }
            State.NOT_RUNNING -> {
                openShizuku(activity)
                onDone("请在 Shizuku 里点启动，完成后再回来点解锁")
            }
            State.NO_PERMISSION -> {
                try {
                    Shizuku.requestPermission(REQ)
                    Toast.makeText(activity, "请在弹窗里允许 Shizuku", Toast.LENGTH_SHORT).show()
                } catch (e: Throwable) {
                    onDone("无法申请 Shizuku 权限：${e.message}")
                }
            }
            State.READY -> runUnlock(activity, onDone)
        }
    }

    private fun runUnlock(activity: Activity, onDone: ((String) -> Unit)?) {
        val args = Shizuku.UserServiceArgs(
            ComponentName(activity.packageName, JevShellService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)

        lateinit var conn: ServiceConnection
        val timedOut = Runnable {
            try { Shizuku.unbindUserService(args, conn, true) } catch (_: Throwable) { }
            activity.runOnUiThread {
                onDone?.invoke("Shizuku 解锁超时，请确认 Shizuku 在运行后再试")
            }
        }
        conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                clearTimeout()
                val result = try {
                    if (binder == null || !binder.pingBinder()) {
                        "Shizuku 服务无效"
                    } else {
                        applyUnlock(activity, IJevShellService.Stub.asInterface(binder))
                    }
                } catch (e: Throwable) {
                    "解锁失败：${e.message}"
                }
                try {
                    Shizuku.unbindUserService(args, this, true)
                } catch (_: Throwable) { }
                activity.runOnUiThread {
                    onDone?.invoke(result)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {}
        }

        try {
            if (Shizuku.getVersion() < 10) {
                onDone?.invoke("Shizuku 版本过低，请升级到 13+")
                return
            }
            timeout = timedOut
            main.postDelayed(timedOut, TIMEOUT_MS)
            Shizuku.bindUserService(args, conn)
            Toast.makeText(activity, "正在通过 Shizuku 解锁…", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            clearTimeout()
            onDone?.invoke("无法连接 Shizuku：${e.message}")
        }
    }

    private fun clearTimeout() {
        timeout?.let { main.removeCallbacks(it) }
        timeout = null
    }

    private fun applyUnlock(activity: Activity, shell: IJevShellService): String {
        val pkg = activity.packageName
        val appops = shell.allowRestrictedSettings(pkg)
        val existing = Settings.Secure.getString(
            activity.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        val merged = OemSettings.mergeA11yComponent(existing, OemSettings.PLAIN_COMPONENT)
        val put = shell.putSecureSetting("enabled_accessibility_services", merged)
        val on = shell.putSecureSetting("accessibility_enabled", "1")
        val ok = OemSettings.isA11yEnabled(activity)
        return buildString {
            append(if (ok) "已解锁并打开无障碍。" else "已执行命令，请再去无障碍列表确认「Jev助手」。")
            append('\n')
            append("appops: ").append(appops.lineSequence().firstOrNull() ?: appops)
            append(" / settings: ").append(put.lineSequence().firstOrNull() ?: put)
            append(" / enable: ").append(on.lineSequence().firstOrNull() ?: on)
        }
    }

    private fun installed(activity: Activity): Boolean =
        runCatching {
            activity.packageManager.getPackageInfo(SHIZUKU_PKG, 0)
            true
        }.getOrDefault(false)

    private fun openShizuku(activity: Activity) {
        val launch = activity.packageManager.getLaunchIntentForPackage(SHIZUKU_PKG)
        if (launch != null) activity.startActivity(launch)
        else explainInstall(activity)
    }

    private fun explainInstall(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle("需要 Shizuku")
            .setMessage(
                "ColorOS 没有「禁止权限监控」时，用 Shizuku 执行和电脑 adb 相同的解锁（只改 Jev助手）。\n\n" +
                    "1. 安装 Shizuku（不用越狱）\n" +
                    "2. 用无线调试启动一次 Shizuku\n" +
                    "3. 回到本页点「Shizuku 解锁无障碍」并允许授权"
            )
            .setPositiveButton("打开 Shizuku 下载页") { _, _ ->
                runCatching {
                    activity.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
                    )
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
