package com.djipower.companion.service

import android.content.Context
import com.djipower.companion.data.AppSettings
import com.djipower.companion.data.Conn
import com.djipower.companion.data.CredStore
import com.djipower.companion.ble.DjiPowerDevice
import com.djipower.companion.protocol.Advanced
import com.djipower.companion.protocol.CarCharger
import com.djipower.companion.protocol.Energy
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Home Assistant side of the MQTT bridge: discovery configs, the retained
 * `<base>/settings` JSON every config entity reads its state from, and the
 * `<base>/set/<id>` command handler. Station entities only appear once the
 * station has reported them (an output, a car charger, a reserve record), so
 * discovery is recomputed whenever the station config changes.
 *
 * Topics:
 *   <base>/state         telemetry (sensors + AC switch)
 *   <base>/settings      retained JSON of every setting below
 *   <base>/set/<id>      commands (ON/OFF, numbers, select option text)
 *   <base>/ac/set        AC on/off (kept for existing automations)
 *   <base>/alarm         station alarm events {"event_type":"alarm","code":hex}
 */
object MqttEntities {
    const val SETTINGS = "settings"
    const val ALARM = "alarm"
    private const val TEMP_C = "°C"
    private const val TEMP_F = "°F"

    // ---- What the station has right now ------------------------------------

    /** Switchable outputs except Power (type 1, never exposed) and AC (own switch). */
    private fun outputs() = Conn.switches.value.filter { it.type != 1 && it.type != 2 }
    private fun cars(): List<CarCharger> =
        Conn.energy.value?.carChargers?.filter { it.controllable } ?: emptyList()
    private fun outKey(type: Int, seq: Int) = "output_${type}_$seq"
    private fun carKey(c: CarCharger) = "car_${c.interfaceType}_${c.seq}"
    private fun carName(c: CarCharger) = c.name + if (c.seq > 1) " ${c.seq}" else ""

    /** Same bounds the app's reserve slider uses. */
    private fun reserveBounds(): Pair<Int, Int>? {
        val l = Conn.energy.value?.limits ?: return null
        return (l.discharge + 5).coerceAtMost(l.recharge) to l.recharge
    }

    private fun acTimeout(): Int? = Conn.deviceInfo.value?.acTimeoutS ?: Conn.energy.value?.acTimeoutSec

    // ---- DJI Homie's own automations (app settings, not station settings) -----

    private class Auto(val id: String, val name: String,
                       val get: (AppSettings) -> Boolean, val set: (AppSettings, Boolean) -> Unit)
    private class AutoNum(val id: String, val name: String, val unit: String, val min: Int, val max: Int,
                          val get: (AppSettings) -> Int, val set: (AppSettings, Int) -> Unit)

    private val AUTOS = listOf(
        Auto("auto_zero_load", "Automation: zero-load AC cutoff",
            { it.zeroLoadEnabled }, { s, v -> s.zeroLoadEnabled = v }),
        Auto("auto_soc_cutoff", "Automation: battery-level AC cutoff",
            { it.socCutoffEnabled }, { s, v -> s.socCutoffEnabled = v }),
        Auto("auto_duty_cycle", "Automation: AC duty cycle",
            { it.dutyCycleEnabled }, { s, v -> s.dutyCycleEnabled = v }),
        Auto("auto_solar_priority", "Automation: solar priority",
            { it.solarPriorityEnabled }, { s, v -> s.solarPriorityEnabled = v }),
        Auto("auto_storm_prep", "Automation: storm prep",
            { it.stormPrepEnabled }, { s, v -> s.stormPrepEnabled = v }),
    )
    private val AUTO_NUMS = listOf(
        AutoNum("zero_load_w", "Zero-load threshold", "W", 0, 500,
            { it.zeroLoadThresholdW }, { s, v -> s.zeroLoadThresholdW = v }),
        AutoNum("zero_load_min", "Zero-load delay", "min", 1, 240,
            { it.zeroLoadMinutes }, { s, v -> s.zeroLoadMinutes = v }),
        AutoNum("soc_cutoff_pct", "Battery cutoff level", "%", 0, 100,
            { it.socCutoffPercent }, { s, v -> s.socCutoffPercent = v }),
        AutoNum("duty_on_min", "Duty cycle on time", "min", 1, 1440,
            { it.dutyOnMin }, { s, v -> s.dutyOnMin = v }),
        AutoNum("duty_period_min", "Duty cycle period", "min", 2, 1440,
            { it.dutyPeriodMin }, { s, v -> s.dutyPeriodMin = v }),
        AutoNum("solar_w", "Solar priority threshold", "W", 0, 5000,
            { it.solarThresholdW }, { s, v -> s.solarThresholdW = v }),
    )

    // ---- Settings JSON --------------------------------------------------------

    fun settingsJson(ctx: Context): String {
        val e = Conn.energy.value
        val di = Conn.deviceInfo.value
        val s = AppSettings(ctx)
        return JSONObject().apply {
            put("station_connected", Conn.device?.authenticated == true)
            put("rssi", Conn.rssi.value ?: JSONObject.NULL)
            outputs().forEach { put(outKey(it.type, it.seq), it.on) }
            e?.limits?.let { put("discharge_limit", it.discharge); put("charge_limit", it.recharge) }
            e?.reserve?.takeIf { it.available }?.let {
                put("reserve_enabled", it.enabled); put("reserve_percent", it.percent)
            }
            e?.energySaverMode?.let { put("energy_saver", it) }
            acTimeout()?.let { put("ups", it == 0) }
            di?.screenTimeoutS?.let { sec -> label(Energy.SCREEN_TIMEOUT_OPTIONS, sec)?.let { put("screen_timeout", it) } }
            di?.deviceTimeoutS?.let { sec -> label(Energy.DEVICE_TIMEOUT_OPTIONS, sec)?.let { put("device_timeout", it) } }
            di?.tempUnitFahrenheit?.let { put("temp_unit", if (it) TEMP_F else TEMP_C) }
            e?.schedule?.let { sc ->
                sc.peakDischarge?.let { put("peak_discharge", it) }
                sc.offPeakCharge?.let { put("offpeak_charge", it) }
                sc.offPeakPower?.let { put("offpeak_power", it.value) }
            }
            cars().forEach { c ->
                val k = carKey(c)
                put("${k}_enabled", c.enabled)
                Advanced.CAR_MODE_NAMES[c.mode]?.let { put("${k}_mode", it) }
                c.fields.forEach { (f, v) ->
                    val scale = Advanced.CAR_FIELD_INFO[f]?.third ?: 1
                    put("${k}_$f", v.value.toDouble() / scale)
                }
            }
            AUTOS.forEach { put(it.id, it.get(s)) }
            AUTO_NUMS.forEach { put(it.id, it.get(s)) }
        }.toString()
    }

    private fun label(opts: List<Pair<Int, String>>, sec: Int) = opts.firstOrNull { it.first == sec }?.second

    // ---- Discovery --------------------------------------------------------------

    /** topic -> retained config payload for every entity that exists right now. */
    fun discovery(ctx: Context, base: String): Map<String, String> {
        val node = base.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val creds = CredStore(ctx)
        val device = JSONObject().apply {
            put("identifiers", JSONArray().put("dji_homie_$node"))
            put("name", creds.stationName?.takeIf { it.isNotBlank() } ?: "DJI Power")
            put("manufacturer", "DJI")
            put("model", creds.model)
        }
        val stateT = "$base/state"
        val settingsT = "$base/$SETTINGS"
        val out = LinkedHashMap<String, String>()

        fun common(id: String, name: String, topic: String) = JSONObject().apply {
            put("name", name)
            put("unique_id", "${node}_$id")
            put("state_topic", topic)
            put("availability_topic", "$base/availability")
            put("device", device)
        }
        fun add(component: String, cfg: JSONObject) {
            out["homeassistant/$component/${cfg.getString("unique_id")}/config"] = cfg.toString()
        }
        fun present(k: String) = "value_json.$k is defined and value_json.$k is not none"
        fun boolTpl(k: String) = "{{ ('ON' if value_json.$k else 'OFF') if ${present(k)} else None }}"
        fun valTpl(k: String) = "{{ value_json.$k if ${present(k)} else None }}"

        fun sensor(id: String, name: String, tpl: String, unit: String?, cls: String?) =
            add("sensor", common(id, name, stateT).apply {
                put("value_template", tpl)
                unit?.let { put("unit_of_measurement", it) }
                cls?.let { put("device_class", it) }
                put("state_class", "measurement")
            })
        fun sw(id: String, name: String, category: String? = "config", icon: String? = null) =
            add("switch", common(id, name, settingsT).apply {
                put("command_topic", "$base/set/$id")
                put("value_template", boolTpl(id))
                put("payload_on", "ON"); put("payload_off", "OFF")
                category?.let { put("entity_category", it) }
                icon?.let { put("icon", it) }
            })
        fun num(id: String, name: String, min: Double, max: Double, step: Double, unit: String?) =
            add("number", common(id, name, settingsT).apply {
                put("command_topic", "$base/set/$id")
                put("value_template", valTpl(id))
                put("min", min); put("max", max); put("step", step)
                unit?.let { put("unit_of_measurement", it) }
                put("mode", "box")
                put("entity_category", "config")
            })
        fun sel(id: String, name: String, options: List<String>) =
            add("select", common(id, name, settingsT).apply {
                put("command_topic", "$base/set/$id")
                put("value_template", valTpl(id))
                put("options", JSONArray(options))
                put("entity_category", "config")
            })

        // Telemetry
        sensor("battery", "Battery", "{{ value_json.battery }}", "%", "battery")
        sensor("output_power", "Output power", "{{ value_json.out_w }}", "W", "power")
        sensor("input_power", "Input power", "{{ value_json.in_w }}", "W", "power")
        sensor("ac_power", "AC output power", "{{ value_json.ac_w }}", "W", "power")
        sensor("dc_power", "DC output power", "{{ value_json.dc_w }}", "W", "power")
        sensor("runtime", "Runtime", "{{ value_json.runtime_min }}", "min", "duration")
        add("binary_sensor", common("charging", "Charging", stateT).apply {
            put("value_template", "{{ 'ON' if value_json.charging else 'OFF' }}")
            put("device_class", "battery_charging")
        })
        add("switch", common("ac", "AC output", stateT).apply {
            put("command_topic", "$base/ac/set")
            put("payload_on", "on"); put("payload_off", "off")
            // null ac_on (switch state not read yet) renders None -> HA shows unknown.
            put("value_template",
                "{{ None if value_json.ac_on is none else ('on' if value_json.ac_on else 'off') }}")
            put("state_on", "on"); put("state_off", "off")
            put("icon", "mdi:power-socket-us")
        })

        // Diagnostics
        add("sensor", common("rssi", "Bluetooth signal", settingsT).apply {
            put("value_template", valTpl("rssi"))
            put("unit_of_measurement", "dBm")
            put("device_class", "signal_strength")
            put("state_class", "measurement")
            put("entity_category", "diagnostic")
        })
        add("binary_sensor", common("station_connected", "Station connected", settingsT).apply {
            put("value_template", boolTpl("station_connected"))
            put("device_class", "connectivity")
            put("entity_category", "diagnostic")
        })
        add("event", common("alarm", "Station alarm", "$base/$ALARM").apply {
            put("event_types", JSONArray().put("alarm"))
            put("icon", "mdi:alert")
        })

        // Outputs
        outputs().forEach { sw(outKey(it.type, it.seq), it.label, category = null, icon = "mdi:power-socket") }

        // Energy management
        val e = Conn.energy.value
        e?.limits?.let { l ->
            num("discharge_limit", "Discharge limit", l.dischargeMin.toDouble(), l.dischargeMax.toDouble(), 1.0, "%")
            num("charge_limit", "Charge limit", l.rechargeMin.toDouble(), l.rechargeMax.toDouble(), 1.0, "%")
        }
        if (e?.reserve?.available == true) {
            sw("reserve_enabled", "Backup reserve")
            reserveBounds()?.let { (lo, hi) ->
                num("reserve_percent", "Backup reserve level", lo.toDouble(), hi.toDouble(), 1.0, "%")
            }
        }
        if (e?.energySaverAvailable == true && e.energySaverModes.isNotEmpty()) {
            sel("energy_saver", "Energy Saver mode", e.energySaverModes)
        }

        // Device settings
        if (acTimeout() != null) sw("ups", "AC always on (UPS)")
        Conn.deviceInfo.value?.let { di ->
            if (di.screenTimeoutS != null) sel("screen_timeout", "Screen timeout", Energy.SCREEN_TIMEOUT_OPTIONS.map { it.second })
            if (di.deviceTimeoutS != null) sel("device_timeout", "Device auto-off", Energy.DEVICE_TIMEOUT_OPTIONS.map { it.second })
            if (di.tempUnitFahrenheit != null) sel("temp_unit", "Temperature unit", listOf(TEMP_C, TEMP_F))
        }

        // Scheduled Periods
        e?.schedule?.let { sc ->
            if (sc.peakDischarge != null) sw("peak_discharge", "Discharge during peak periods")
            if (sc.offPeakCharge != null) sw("offpeak_charge", "Charge during off-peak periods")
            sc.offPeakPower?.let { f ->
                num("offpeak_power", "Off-peak charging power", f.min.toDouble(), f.max.toDouble(), 1.0, "W")
            }
        }

        // Car chargers
        cars().forEach { c ->
            val k = carKey(c)
            sw("${k}_enabled", carName(c), category = null, icon = "mdi:car-electric")
            sel("${k}_mode", "${carName(c)} mode", Advanced.CAR_MODE_NAMES.values.toList())
            c.fields.forEach { (f, v) ->
                val info = Advanced.CAR_FIELD_INFO[f] ?: return@forEach
                if (v.max <= 0) return@forEach
                val scale = info.third.toDouble()
                num("${k}_$f", "${carName(c)} ${info.first.lowercase()}",
                    v.min / scale, v.max / scale, 1.0 / scale, info.second)
            }
        }

        // DJI Homie automations
        AUTOS.forEach { sw(it.id, it.name, icon = "mdi:robot") }
        AUTO_NUMS.forEach { num(it.id, it.name, it.min.toDouble(), it.max.toDouble(), 1.0, it.unit) }

        return out
    }

    // ---- Commands -----------------------------------------------------------------

    private fun number(p: String): Double =
        p.toDoubleOrNull() ?: throw IllegalArgumentException("not a number: $p")

    private fun seconds(opts: List<Pair<Int, String>>, p: String): Int =
        opts.firstOrNull { it.second == p }?.first ?: throw IllegalArgumentException("unknown option: $p")

    /** Apply one `<base>/set/<id>` command. Throws with a readable message on bad input. */
    suspend fun handle(ctx: Context, id: String, payload: String) {
        val p = payload.trim()
        val on = p.uppercase() in setOf("ON", "1", "TRUE")

        AUTOS.firstOrNull { it.id == id }?.let {
            it.set(AppSettings(ctx), on)
            DjiPowerService.sync(ctx)
            return
        }
        AUTO_NUMS.firstOrNull { it.id == id }?.let {
            val v = number(p).roundToInt()
            require(v in it.min..it.max) { "${it.name} must be ${it.min}–${it.max} ${it.unit}" }
            it.set(AppSettings(ctx), v)
            return
        }

        val dev = Conn.device?.takeIf { it.authenticated }
            ?: throw IllegalStateException("station not connected")
        val e = Conn.energy.value
        when {
            id.startsWith("output_") -> {
                val (type, seq) = id.removePrefix("output_").split("_").map { it.toInt() }
                require(type != 1) { "the Power switch is not controllable" }
                dev.setPortSwitch(type, seq, on)
            }
            id == "discharge_limit" -> {
                val l = e?.limits ?: throw IllegalStateException("limits not loaded yet")
                val v = number(p).roundToInt()
                require(v in l.dischargeMin..l.dischargeMax && v < l.recharge) {
                    "discharge limit must be ${l.dischargeMin}–${minOf(l.dischargeMax, l.recharge - 1)}%"
                }
                dev.setChargeLimits(v, l.recharge)
            }
            id == "charge_limit" -> {
                val l = e?.limits ?: throw IllegalStateException("limits not loaded yet")
                val v = number(p).roundToInt()
                require(v in l.rechargeMin..l.rechargeMax && v > l.discharge) {
                    "charge limit must be ${maxOf(l.rechargeMin, l.discharge + 1)}–${l.rechargeMax}%"
                }
                dev.setChargeLimits(l.discharge, v)
            }
            id == "reserve_enabled" -> dev.setReserve(enabled = on)
            id == "reserve_percent" -> {
                val (lo, hi) = reserveBounds() ?: throw IllegalStateException("limits not loaded yet")
                val v = number(p).roundToInt()
                require(v in lo..hi) { "reserve level must be $lo–$hi%" }
                dev.setReserve(percent = v, min = lo, max = hi)
            }
            id == "energy_saver" -> {
                require(e?.energySaverModes?.contains(p) == true) { "mode not available: $p" }
                dev.setEnergySaverMode(p)
            }
            id == "ups" -> dev.setAcAlwaysOn(on)
            id == "screen_timeout" -> dev.setScreenTimeout(seconds(Energy.SCREEN_TIMEOUT_OPTIONS, p))
            id == "device_timeout" -> dev.setDeviceTimeout(seconds(Energy.DEVICE_TIMEOUT_OPTIONS, p))
            id == "temp_unit" -> dev.setTempUnitFahrenheit(
                when (p) { TEMP_F -> true; TEMP_C -> false; else -> throw IllegalArgumentException("use °C or °F") },
            )
            id == "peak_discharge" -> {
                if (on) require(e?.schedule?.periods?.any { it.peak } == true) { "add a peak period in the app first" }
                dev.setScheduledSwitch(true, on)
            }
            id == "offpeak_charge" -> {
                if (on) require(e?.schedule?.periods?.any { !it.peak } == true) { "add an off-peak period in the app first" }
                dev.setScheduledSwitch(false, on)
            }
            id == "offpeak_power" -> dev.setOffPeakChargePower(number(p).roundToInt())
            id.startsWith("car_") -> handleCar(dev, id, p, on)
            else -> throw IllegalArgumentException("unknown setting: $id")
        }
    }

    private suspend fun handleCar(dev: DjiPowerDevice, id: String, p: String, on: Boolean) {
        val c = cars().firstOrNull { id.startsWith(carKey(it) + "_") }
            ?: throw IllegalStateException("car charger not found")
        when (val rest = id.removePrefix(carKey(c) + "_")) {
            "enabled" -> dev.setCarChargerEnabled(c, on)
            "mode" -> {
                val mode = Advanced.CAR_MODE_NAMES.entries.firstOrNull { it.value == p }?.key
                    ?: throw IllegalArgumentException("unknown mode: $p")
                dev.setCarChargerMode(c, mode)
            }
            in Advanced.CAR_FIELDS -> {
                val scale = Advanced.CAR_FIELD_INFO[rest]?.third ?: 1
                dev.setCarChargerField(c, rest, (number(p) * scale).roundToInt())
            }
            else -> throw IllegalArgumentException("unknown car-charger setting: $rest")
        }
    }
}
