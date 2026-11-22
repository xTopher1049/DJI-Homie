package com.djipower.companion.data

import android.content.Context

/**
 * Non-secret app settings (automation toggles and the background-service gate).
 * Kept separate from the encrypted credential store.
 */
class AppSettings(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("dji_homie_settings", Context.MODE_PRIVATE)

    /**
     * True when at least one feature that needs the always-on service is enabled.
     * The service only runs while this is set, so nothing runs in the background
     * until the user turns on a background automation.
     */
    var serviceEnabled: Boolean
        get() = prefs.getBoolean("service_enabled", false)
        set(v) = prefs.edit().putBoolean("service_enabled", v).apply()
}
