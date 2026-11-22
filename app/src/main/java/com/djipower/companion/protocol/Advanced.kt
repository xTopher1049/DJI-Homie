package com.djipower.companion.protocol

import java.io.ByteArrayOutputStream
import java.math.BigInteger

/** One car-charger setting: the station's bounds and current setpoint, in raw units. */
data class CarField(val max: Int, val min: Int, val value: Int)

/** An SDC car-charger accessory row from the 0x0A record. [index] is its TLV position. */
data class CarCharger(
    val index: Int,
    val interfaceType: Int,
    val seq: Int,
    val type: Int,
    val sw: Int,
    val mode: Int,
    val fields: Map<String, CarField>,
) {
    val enabled: Boolean get() = sw == 1

    /** DJI Home exposes controls for the 1 kW (3) and 1.8 kW (4) SDC car chargers. */
    val controllable: Boolean
        get() = type in intArrayOf(3, 4) && interfaceType in intArrayOf(5, 6) &&
            sw in intArrayOf(1, 2) && mode in 1..3

    val name: String
        get() = when (type) {
            3 -> "Car charger (1 kW)"
            4 -> "Car charger (1.8 kW)"
            else -> "Car charger"
        }
}

/** A recurring electricity-price period. Minutes from midnight; days 0 = Mon … 6 = Sun.
 *  An end earlier than the start runs into the next day. */
data class TimePeriod(val peak: Boolean, val days: Set<Int>, val start: Int, val end: Int)

/** Scheduled Periods state: price periods (0x16) + the switches/power in the eco record. */
data class Schedule(
    val periods: List<TimePeriod>?,
    val peakDischarge: Boolean?,
    val offPeakCharge: Boolean?,
    val offPeakPower: CarField?,
)

/**
 * Car chargers (0x0A), station rules (0x0E), electricity price periods (0x16) and the
 * Scheduled Periods controls stored in the eco record (0x18). Layouts match DJI Home
 * (cross-checked against the ha-dji-power-ble integration).
 */
object Advanced {
    const val CAR_CHARGERS_KEY = 0x0A
    const val RULES_KEY = 0x0E
    const val TIME_PERIODS_KEY = 0x16
    const val ENERGY_SAVER_RULE = 5
    private const val AUTO_THRESHOLD_RULE = 0

    val CAR_FIELDS = listOf("p_from_car", "p_to_car", "v_from_car", "v_to_car", "v_auto")
    val CAR_MODE_NAMES = mapOf(1 to "Auto", 2 to "Recharge", 3 to "Charge")
    val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    /** Label, unit, raw units per displayed unit (voltages are 0.01 V). */
    val CAR_FIELD_INFO = mapOf(
        "p_from_car" to Triple("Recharge power", "W", 1),
        "p_to_car" to Triple("Charge power", "W", 1),
        "v_from_car" to Triple("Minimum voltage", "V", 100),
        "v_to_car" to Triple("Charge voltage", "V", 100),
        "v_auto" to Triple("Auto switch voltage", "V", 100),
    )

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun u32(b: ByteArray, i: Int) = u16(b, i) or (u16(b, i + 2) shl 16)
    private fun le32(v: Int) =
        byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    // ---- Station rules (0x0E): u16 text length + ASCII hex (u16 count, LE mask) ----
    fun parseRules(v: ByteArray): List<Int>? {
        if (v.size < 2 || v.size != 2 + u16(v, 0)) return null
        val text = v.copyOfRange(2, v.size).takeWhile { it.toInt() != 0 }
            .toByteArray().toString(Charsets.US_ASCII)
        if (text.length < 4) return emptyList()
        val bytes = runCatching { Duml.hexToBytes(text) }.getOrNull() ?: return null
        if (bytes.size < 2) return emptyList()
        val count = u16(bytes, 0)
        val mask = BigInteger(1, bytes.copyOfRange(2, bytes.size).reversedArray())
        return (0 until minOf(count, mask.bitLength())).filter { mask.testBit(it) }
    }

    /** Rule 0 picks Auto mode's single switching voltage; null when rules are unknown. */
    fun autoThreshold(rules: List<Int>?): Boolean? = rules?.contains(AUTO_THRESHOLD_RULE)

    // ---- Car chargers (0x0A) ----
    fun parseCarChargers(v: ByteArray): List<CarCharger> =
        Report.parseTlvs(v).mapIndexedNotNull { i, t ->
            val r = t.value
            if (r.size < 65) return@mapIndexedNotNull null
            val fields = CAR_FIELDS.mapIndexed { fi, name ->
                val o = 5 + fi * 12
                name to CarField(max = u32(r, o), min = u32(r, o + 4), value = u32(r, o + 8))
            }.toMap()
            CarCharger(i, u8(r, 0), u8(r, 1), u8(r, 2), u8(r, 3), u8(r, 4), fields)
        }

    /** Settings DJI Home lets you edit in a mode (1 Auto, 2 Recharge, 3 Charge). */
    fun editableFields(mode: Int, autoThreshold: Boolean?): List<String> {
        val base = when (mode) {
            1 -> listOf("p_from_car", "p_to_car")
            2 -> listOf("p_from_car", "v_from_car")
            3 -> listOf("p_to_car", "v_to_car")
            else -> emptyList()
        }
        if (mode != 1 || autoThreshold == null) return base
        return base + if (autoThreshold) listOf("v_auto") else listOf("v_from_car", "v_to_car")
    }

    /** Rewrite one byte range of one row, re-tagging every row with the app's SET tag. */
    fun withCarChargerRow(snapshot: ByteArray, index: Int, offset: Int, repl: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        Report.parseTlvs(snapshot).forEachIndexed { i, t ->
            val row = t.value.copyOf()
            if (i == index) System.arraycopy(repl, 0, row, offset, repl.size)
            out.write(
                byteArrayOf(CAR_CHARGERS_KEY.toByte(), 0, row.size.toByte(), (row.size shr 8).toByte()),
            )
            out.write(row)
        }
        return out.toByteArray()
    }

    fun carEnabledEdit(on: Boolean) = 3 to byteArrayOf((if (on) 1 else 2).toByte())
    fun carModeEdit(mode: Int) = 4 to byteArrayOf(mode.toByte())
    fun carFieldEdit(field: String, raw: Int) = (5 + CAR_FIELDS.indexOf(field) * 12 + 8) to le32(raw)

    // ---- Electricity price periods (0x16): 10-byte rows ----
    fun parseTimePeriods(v: ByteArray): List<TimePeriod>? {
        val out = ArrayList<TimePeriod>()
        for (t in Report.parseTlvs(v)) {
            val r = t.value
            if (r.size != 10) return null
            val kind = u8(r, 0)
            val rep = u8(r, 1)
            if (kind !in 1..2 || rep !in 1..2) return null
            val mask = u32(r, 2)
            val days = if (rep == 1) (0..6).toSet() else (0..6).filter { (mask shr it) and 1 == 1 }.toSet()
            if (days.isEmpty()) return null
            out.add(TimePeriod(kind == 1, days, u8(r, 6) * 60 + u8(r, 7), u8(r, 8) * 60 + u8(r, 9)))
        }
        return sortPeriods(out)
    }

    private fun mask(days: Set<Int>) = days.sumOf { 1 shl it }

    fun sortPeriods(p: List<TimePeriod>): List<TimePeriod> =
        p.sortedWith(compareBy({ if (it.peak) 1 else 2 }, { it.start }, { it.end }, { mask(it.days) }))

    /** Null when the schedule is valid, otherwise why not. */
    fun validatePeriods(p: List<TimePeriod>): String? {
        if (p.count { it.peak } > 8 || p.count { !it.peak } > 8) {
            return "At most 8 peak and 8 off-peak periods"
        }
        val week = 7 * 1440
        val iv = ArrayList<Pair<Int, Int>>()
        for (x in p) {
            if (x.days.isEmpty()) return "Each period needs at least one day"
            if (x.start == x.end) return "A period's start and end must differ"
            for (d in x.days) {
                val base = d * 1440
                val stop = base + x.end + if (x.end < x.start) 1440 else 0
                if (stop > week) {
                    iv.add(base + x.start to week)
                    iv.add(0 to stop - week)
                } else {
                    iv.add(base + x.start to stop)
                }
            }
        }
        iv.sortBy { it.first }
        for (i in 1 until iv.size) if (iv[i].first < iv[i - 1].second) return "Periods must not overlap"
        return null
    }

    fun encodeTimePeriods(p: List<TimePeriod>): ByteArray {
        val out = ByteArrayOutputStream()
        for (x in sortPeriods(p)) {
            val m = mask(x.days)
            out.write(byteArrayOf(TIME_PERIODS_KEY.toByte(), 0, 10, 0))
            out.write(byteArrayOf((if (x.peak) 1 else 2).toByte(), (if (m == 0x7F) 1 else 2).toByte()))
            out.write(le32(m))
            out.write(
                byteArrayOf(
                    (x.start / 60).toByte(), (x.start % 60).toByte(),
                    (x.end / 60).toByte(), (x.end % 60).toByte(),
                ),
            )
        }
        return out.toByteArray()
    }

    // ---- Scheduled Periods controls inside the eco record (0x18) ----
    fun parseSchedule(eco: ByteArray?, periodsRaw: ByteArray?): Schedule? {
        val periods = periodsRaw?.let { parseTimePeriods(it) }
        if (eco == null || eco.size < 86) return periods?.let { Schedule(it, null, null, null) }
        val max = u32(eco, 4)
        val min = u32(eco, 8)
        val w = u32(eco, 12)
        return Schedule(
            periods = periods,
            peakDischarge = u8(eco, 2) == 1,
            offPeakCharge = u8(eco, 3) == 1,
            offPeakPower = if (max > 0 && w in min..max) CarField(max, min, w) else null,
        )
    }

    fun withScheduledSwitch(eco: ByteArray, peak: Boolean, on: Boolean): ByteArray {
        require(eco.size >= 86) { "unexpected eco record size" }
        return eco.copyOf().also { it[if (peak) 2 else 3] = (if (on) 1 else 2).toByte() }
    }

    fun withOffPeakPower(eco: ByteArray, watts: Int): ByteArray {
        require(eco.size >= 86) { "unexpected eco record size" }
        val max = u32(eco, 4)
        val min = u32(eco, 8)
        require(watts in min..max) { "Off-peak charging power must be $min–$max W" }
        return eco.copyOf().also { System.arraycopy(le32(watts), 0, it, 12, 4) }
    }

    fun fmtTime(min: Int) = "%02d:%02d".format(min / 60, min % 60)

    /** "HH:MM" → minutes, or null. */
    fun parseTime(s: String): Int? {
        val m = Regex("""^(\d{1,2}):(\d{2})$""").find(s.trim()) ?: return null
        val h = m.groupValues[1].toInt()
        val mi = m.groupValues[2].toInt()
        return if (h in 0..23 && mi in 0..59) h * 60 + mi else null
    }
}
