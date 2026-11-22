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
    private fun f(k: String, d: Float) = prefs.getFloat(k, d)
    private fun setF(k: String, v: Float) = prefs.edit().putFloat(k, v).apply()
    private fun str(k: String, d: String) = prefs.getString(k, d) ?: d
    private fun setStr(k: String, v: String) = prefs.edit().putString(k, v).apply()
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

    // --- Storm prep: auto charge-to-full during a hurricane/tropical-storm alert ---
    var stormPrepEnabled: Boolean
        get() = b("stormprep_on", false); set(v) = setB("stormprep_on", v)
    var stormLat: Float
        get() = f("storm_lat", 0f); set(v) = setF("storm_lat", v)
    var stormLon: Float
        get() = f("storm_lon", 0f); set(v) = setF("storm_lon", v)
    var stormSavedCharge: Int
        get() = i("storm_saved", -1); set(v) = setI("storm_saved", v)
    var stormActive: Boolean
        get() = b("storm_active", false); set(v) = setB("storm_active", v)

    // --- Solar / alternator priority (AC on only while input exceeds threshold) ---
    var solarPriorityEnabled: Boolean
        get() = b("solar_on", false); set(v) = setB("solar_on", v)
    var solarThresholdW: Int
        get() = i("solar_w", 200); set(v) = setI("solar_w", v)

    // --- MQTT bridge (publish telemetry + accept AC commands over a local broker) ---
    var mqttEnabled: Boolean
        get() = b("mqtt_on", false); set(v) = setB("mqtt_on", v)
    var mqttHost: String
        get() = str("mqtt_host", ""); set(v) = setStr("mqtt_host", v)
    var mqttPort: Int
        get() = i("mqtt_port", 1883); set(v) = setI("mqtt_port", v)
    var mqttUser: String
        get() = str("mqtt_user", ""); set(v) = setStr("mqtt_user", v)
    var mqttPass: String
        get() = str("mqtt_pass", ""); set(v) = setStr("mqtt_pass", v)
    var mqttBaseTopic: String
        get() = str("mqtt_topic", "dji_power"); set(v) = setStr("mqtt_topic", v)

    /** Start the background service when the phone boots (only matters while an
     *  automation or MQTT is on). On by default, matching the previous behaviour. */
    var startOnBoot: Boolean
        get() = b("start_on_boot", true); set(v) = setB("start_on_boot", v)

    /** Shared secret Tasker / MacroDroid must send as the "token" extra with
     *  SET_AC, so other installed apps can't drive the station. Generated once. */
    val commandToken: String
        get() = prefs.getString("cmd_token", null) ?: regenerateCommandToken()

    fun regenerateCommandToken(): String {
        val bytes = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val t = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString("cmd_token", t).apply()
        return t
    }

    /** True when any automation that needs the always-on service is enabled. */
    val serviceEnabled: Boolean
        get() = zeroLoadEnabled || socCutoffEnabled || dutyCycleEnabled ||
            solarPriorityEnabled || stormPrepEnabled || mqttEnabled

    // --- Home-screen widget snapshot (last-known telemetry) ---
    val wBattery: Int get() = i("w_batt", -1)
    val wOut: Int get() = i("w_out", 0)
    val wIn: Int get() = i("w_in", 0)
    val wRuntime: Int get() = i("w_rt", -1)

    fun writeWidget(battery: Int, outW: Int, inW: Int, runtimeMin: Int) {
        prefs.edit()
            .putInt("w_batt", battery).putInt("w_out", outW)
            .putInt("w_in", inW).putInt("w_rt", runtimeMin)
            .apply()
    }

    fun clearAll() = prefs.edit().clear().apply()
}
