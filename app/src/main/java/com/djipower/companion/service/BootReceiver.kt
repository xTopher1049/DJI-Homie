package com.djipower.companion.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.djipower.companion.data.AppSettings
import com.djipower.companion.data.CredStore

/**
 * Restarts the monitor service after a reboot or an app update (installing an
 * update kills the process, foreground service included), but only if the user
 * has onboarded a station AND enabled a background automation. Android holds a
 * freshly installed app in a "stopped" state, so this first fires only after
 * the user has opened the app at least once. Both broadcasts are exempt from
 * Android 12+'s background foreground-service start restriction.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (CredStore(context).isOnboarded && AppSettings(context).serviceEnabled) {
            DjiPowerService.start(context)
        }
    }
}
