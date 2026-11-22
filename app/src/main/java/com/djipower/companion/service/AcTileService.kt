package com.djipower.companion.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.djipower.companion.data.Conn
import com.djipower.companion.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Quick Settings tile that toggles the AC inverter. Works when a connection is
 *  live (app open or an automation running); otherwise it opens the app. */
class AcTileService : TileService() {
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        val device = Conn.device
        val acOn = Conn.switches.value.firstOrNull { it.type == 2 }?.on
        if (device != null && acOn != null) {
            // On failure, snap the tile back to the real switch state.
            scope.launch { runCatching { device.setAcOutput(!acOn) }.onFailure { render() } }
            // optimistic flip; the next 0x61 report confirms it
            qsTile?.state = if (acOn) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            qsTile?.updateTile()
        } else {
            val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE),
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(i)
            }
        }
    }

    private fun render() {
        val tile = qsTile ?: return
        tile.label = "DJI AC"
        tile.state = when (Conn.switches.value.firstOrNull { it.type == 2 }?.on) {
            true -> Tile.STATE_ACTIVE
            false -> Tile.STATE_INACTIVE
            null -> Tile.STATE_UNAVAILABLE
        }
        tile.updateTile()
    }
}
