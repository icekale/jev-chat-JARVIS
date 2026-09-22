package com.jev.probe.capture

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.jev.probe.R
import com.jev.probe.core.Prefs

/** Quick Settings tile. On keeps the foreground service up; opening the shade refreshes it. */
class KeepAliveTile : TileService() {

    override fun onStartListening() {
        paint()
        KeepAliveService.sync(this)
        if (Prefs(this).enabled) poke()
    }

    override fun onClick() {
        val prefs = Prefs(this)
        prefs.enabled = !prefs.enabled
        KeepAliveService.sync(this)
        if (prefs.enabled) poke()
        paint()
    }

    private fun paint() {
        val tile = qsTile ?: return
        val on = Prefs(this).enabled
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Jev"
        tile.subtitle = if (on) "保活中" else "已暂停"
        tile.updateTile()
    }

    private fun poke() {
        runCatching {
            sendBroadcast(Intent(ChatCaptureService.ACTION_WAKE).setPackage(packageName))
        }
    }

    companion object {
        fun refresh(ctx: android.content.Context) {
            runCatching {
                requestListeningState(ctx, ComponentName(ctx, KeepAliveTile::class.java))
            }
        }

        fun askToAdd(activity: android.app.Activity) {
            if (Build.VERSION.SDK_INT < 33) {
                Toast.makeText(activity, "下拉通知栏，点编辑，把 Jev 拖进去", Toast.LENGTH_LONG).show()
                return
            }
            val sbm = activity.getSystemService(StatusBarManager::class.java) ?: return
            sbm.requestAddTileService(
                ComponentName(activity, KeepAliveTile::class.java),
                "Jev",
                Icon.createWithResource(activity, R.drawable.ic_launcher_foreground),
                activity.mainExecutor
            ) { result ->
                val msg = when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "已加到下拉通知栏"
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "通知栏里已经有 Jev"
                    else -> "没有加上。下拉通知栏，点编辑，把 Jev 拖进去"
                }
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
