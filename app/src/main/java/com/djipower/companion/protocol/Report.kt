package com.djipower.companion.protocol

/**
 * Parsers for DJI Power 0x61 telemetry reports and 0x60 keyed-config responses.
 * Port of the relevant parts of duml.py (parse_report / parse_keyed_values).
 */

data class Tlv(val tag: Int, val value: ByteArray)

/** One decoded telemetry snapshot. Nulls mean "not present in this report". */
data class PowerState(
    val batteryPercent: Double? = null,
    val runtimeMin: Int? = null,
    val temperatureC: Double? = null,
    val outputW: Int? = null,
    val inputW: Int? = null,
    val acOutputW: Int = 0,
    val dcOutputW: Int = 0,
    val chargeInputW: Int = 0,
    val charging: Boolean? = null,
    val ports: List<PortPower> = emptyList(),
    /** Raw battery-state byte (0x3020[12]); 1 recharging, 2 discharging, others unmapped. */
    val chargeStateRaw: Int? = null,
)

/** One active port from a telemetry report: identity + live watts. */
data class PortPower(
    val type: Int, val seq: Int, val label: String,
    val outputW: Int, val inputW: Int, val on: Boolean,
)

object Report {
    private val INTERFACE_TYPE_NAMES = mapOf(
        0 to "unknown", 1 to "power", 2 to "ac", 3 to "usb_a", 4 to "usb_c",
        5 to "sdc", 6 to "sdc_lite", 7 to "cigarette", 8 to "xt60",
    )

    private fun le16(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    private fun le16s(b: ByteArray, i: Int): Int {
        val v = le16(b, i)
        return if (v >= 0x8000) v - 0x10000 else v
    }

    /**
     * Fallback when the battery-state byte is missing or holds a value we haven't
     * mapped (seen while solar-charging with no load): judge by net power flow.
     * Within ±[FLOW_DEADBAND_W] it's genuinely idle, so stay unknown.
     */
    fun chargingFromFlow(inputW: Int?, outputW: Int?): Boolean? {
        if (inputW == null || outputW == null) return null
        return when {
            inputW - outputW > FLOW_DEADBAND_W -> true
            outputW - inputW > FLOW_DEADBAND_W -> false
            else -> null
        }
    }

    private const val FLOW_DEADBAND_W = 10

    fun parseTlvs(data: ByteArray): List<Tlv> {
        val out = ArrayList<Tlv>()
        var off = 0
        while (off + 4 <= data.size) {
            val tag = le16(data, off)
            val len = le16(data, off + 2)
            val end = off + 4 + len
            if (end > data.size) break
            out.add(Tlv(tag, data.copyOfRange(off + 4, end)))
            off = end
        }
        return out
    }

    private fun records(records: List<Tlv>, tag: Int): List<ByteArray> =
        records.filter { it.tag == tag }.map { it.value }

    private fun reportRecords(payload: ByteArray): List<Tlv> {
        val body = if (payload.size >= 20 && payload[2].toInt() == 0x10 && payload[3].toInt() == 0x00)
            payload.copyOfRange(16, payload.size) else payload
        return parseTlvs(body)
    }

    /** Decode a 0x5a/0x61 battery and power push. */
    fun parseReport(payload: ByteArray): PowerState {
        val top = reportRecords(payload)
        var batteryPercent: Double? = null
        var runtimeMin: Int? = null
        var temperatureC: Double? = null
        var charging: Boolean? = null
        var chargeStateRaw: Int? = null

        records(top, 0x3020).firstOrNull()?.let { battery ->
            if (battery.size >= 9) {
                batteryPercent = le16(battery, 0) / 100.0
                runtimeMin = le16(battery, 2)
            }
            if (battery.size >= 12 && battery[11].toInt() in intArrayOf(1, 2, 3)) {
                temperatureC = le16s(battery, 9) / 100.0
            }
            if (battery.size >= 13) {
                chargeStateRaw = battery[12].toInt() and 0xFF
                charging = when (chargeStateRaw) {
                    1 -> true   // recharging
                    2 -> false  // discharging
                    else -> null
                }
            }
        }

        var outputW: Int? = null
        var inputW: Int? = null
        val groupOutput = HashMap<Int, Int>()
        val groupInput = HashMap<Int, Int>()
        val ports = ArrayList<PortPower>()

        records(top, 0x3030).firstOrNull()?.let { power ->
            if (power.size >= 4) {
                outputW = le16(power, 0)
                inputW = le16(power, 2)
                val rest = power.copyOfRange(4, power.size)
                for (container in records(parseTlvs(rest), 0x3031)) {
                    for (group in records(parseTlvs(container), 0x3032)) {
                        if (group.isEmpty()) continue
                        val groupType = group[0].toInt() and 0xFF
                        for (wrapper in records(parseTlvs(group.copyOfRange(1, group.size)), 0x3033)) {
                            for (port in records(parseTlvs(wrapper), 0x3034)) {
                                if (port.size < 7) continue
                                val outW = le16(port, 3)
                                val inW = le16(port, 5)
                                val pType = port[1].toInt() and 0xFF
                                val pSeq = port[0].toInt() and 0xFF
                                ports.add(
                                    PortPower(
                                        type = pType, seq = pSeq,
                                        label = Energy.switchLabel(pType, pSeq),
                                        outputW = outW, inputW = inW,
                                        on = (port[2].toInt() and 0xFF) == 1,
                                    )
                                )
                                groupOutput[groupType] = (groupOutput[groupType] ?: 0) + outW
                                groupInput[groupType] = (groupInput[groupType] ?: 0) + inW
                            }
                        }
                    }
                }
            }
        }

        return PowerState(
            batteryPercent = batteryPercent,
            runtimeMin = runtimeMin,
            temperatureC = temperatureC,
            outputW = outputW,
            inputW = inputW,
            acOutputW = groupOutput[2] ?: 0,
            dcOutputW = groupOutput[3] ?: 0,
            chargeInputW = groupInput[1] ?: 0,
            charging = charging ?: chargingFromFlow(inputW, outputW),
            ports = ports,
            chargeStateRaw = chargeStateRaw,
        )
    }
}
