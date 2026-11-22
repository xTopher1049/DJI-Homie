package com.djipower.companion.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.djipower.companion.data.Conn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Lets Tasker / MacroDroid control the station via broadcast intents:
 *   adb/Tasker -> action com.djipower.companion.SET_AC  extra boolean "on"
 * Best-effort: requires a live connection (app open or an automation running).
 */
class CommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SET_AC -> {
                val on = intent.getBooleanExtra("on", false)
                val device = Conn.device ?: return
                CoroutineScope(Dispatchers.Main).launch {
                    runCatching { device.setAcOutput(on) }
                }
            }
        }
    }

    companion object {
        const val ACTION_SET_AC = "com.djipower.companion.SET_AC"
    }
}
