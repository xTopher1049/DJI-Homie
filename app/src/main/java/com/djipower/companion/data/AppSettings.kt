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

    // --- Storm / outage reserve (shed AC after charging input is lost) ---
    var stormReserveEnabled: Boolean
        get() = b("storm_on", false); set(v) = setB("storm_on", v)
    var stormMinutes: Int
        get() = i("storm_min", 5); set(v) = setI("storm_min", v)

    // --- Solar / alternator priority (AC on only while input exceeds threshold) ---
    var solarPriorityEnabled: Boolean
        get() = b("solar_on", false); set(v) = setB("solar_on", v)
    var solarThresholdW: Int
        get() = i("solar_w", 200); set(v) = setI("solar_w", v)

    /** True when any automation that needs the always-on service is enabled. */
    val serviceEnabled: Boolean
        get() = zeroLoadEnabled || socCutoffEnabled || dutyCycleEnabled ||
            stormReserveEnabled || solarPriorityEnabled

    fun clearAll() = prefs.edit().clear().apply()
}
