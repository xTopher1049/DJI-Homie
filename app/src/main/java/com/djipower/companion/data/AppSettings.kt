package com.djipower.companion.data

import android.content.Context

/**
 * Non-secret app settings: the Phase 2 automation toggles and their parameters.
 * Kept separate from the encrypted credential store. The background service
 * runs only while [serviceEnabled] is true, i.e. at least one automation is on.
 */
class AppSettings(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("dji_homie_settings", Context.MODE_PRIVATE)

    private fun b(k: String, d: Boolean) = prefs.getBoolean(k, d)
    private fun setB(k: String, v: Boolean) = prefs.edit().putBoolean(k, v).apply()
    private fun i(k: String, d: Int) = prefs.getInt(k, d)
    private fun setI(k: String, v: Int) = prefs.edit().putInt(k, v).apply()

    // --- Zero-load AC cutoff (inverter parasitic-drain protection) ---
    var zeroLoadEnabled: Boolean
        get() = b("zl_on", false); set(v) = setB("zl_on", v)
    var zeroLoadThresholdW: Int
        get() = i("zl_w", 5); set(v) = setI("zl_w", v)
    var zeroLoadMinutes: Int
        get() = i("zl_min", 10); set(v) = setI("zl_min", v)

    // --- SOC discharge cutoff (reserve emergency DC/USB power) ---
    var socCutoffEnabled: Boolean
        get() = b("soc_on", false); set(v) = setB("soc_on", v)
    var socCutoffPercent: Int
        get() = i("soc_pct", 15); set(v) = setI("soc_pct", v)

    // --- AC duty-cycle (intermittent loads: on N min of every period) ---
    var dutyCycleEnabled: Boolean
        get() = b("duty_on", false); set(v) = setB("duty_on", v)
    var dutyOnMin: Int
        get() = i("duty_on_min", 20); set(v) = setI("duty_on_min", v)
    var dutyPeriodMin: Int
        get() = i("duty_period_min", 60); set(v) = setI("duty_period_min", v)

    // --- Idle power-down (master power off when fully idle) ---
    var idlePowerDownEnabled: Boolean
        get() = b("idle_on", false); set(v) = setB("idle_on", v)
    var idleMinutes: Int
        get() = i("idle_min", 30); set(v) = setI("idle_min", v)

    /** True when any automation that needs the always-on service is enabled. */
    val serviceEnabled: Boolean
        get() = zeroLoadEnabled || socCutoffEnabled || dutyCycleEnabled || idlePowerDownEnabled

    fun clearAll() = prefs.edit().clear().apply()
}
