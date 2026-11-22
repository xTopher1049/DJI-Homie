package com.djipower.companion.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.djipower.companion.data.AppSettings
import com.djipower.companion.data.Conn
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Lets Tasker / MacroDroid control the station via broadcast intents:
 *   action com.djipower.companion.SET_AC, boolean extra "on",
 *   string extra "token" = the token shown in the app's Automations tab.
 * The receiver is exported (Tasker needs that), so the token is what stops any
 * other installed app from switching the station. Best-effort: requires a live
 * connection (app open or an automation running).
 */
class CommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SET_AC -> {
                val expected = AppSettings(context).commandToken
                val given = intent.getStringExtra(EXTRA_TOKEN) ?: return
                if (!MessageDigest.isEqual(given.toByteArray(), expected.toByteArray())) return
                val on = intent.getBooleanExtra("on", false)
                val device = Conn.device ?: return
                // Keep the receiver alive until the BLE write finishes.
                val result = goAsync()
                CoroutineScope(Dispatchers.Main).launch {
                    try { runCatching { device.setAcOutput(on) } } finally { result.finish() }
                }
            }
        }
    }

    companion object {
        const val ACTION_SET_AC = "com.djipower.companion.SET_AC"
        const val EXTRA_TOKEN = "token"
    }
}
