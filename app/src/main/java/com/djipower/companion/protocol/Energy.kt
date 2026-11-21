package com.djipower.companion.protocol

/** Decoded Energy Management config (0x05 charge limits, 0x06 backup reserve). */
data class ChargeLimits(
    val discharge: Int, val dischargeMin: Int, val dischargeMax: Int,
    val recharge: Int, val rechargeMin: Int, val rechargeMax: Int,
)
data class Reserve(val available: Boolean, val enabled: Boolean, val percent: Int)
data class EnergyConfig(val limits: ChargeLimits, val reserve: Reserve?, val acTimeoutSec: Int? = null)

/** One switchable station output (from the 0x0D power-switch snapshot). */
data class PortSwitch(val type: Int, val seq: Int, val on: Boolean, val label: String)

object Energy {
    const val CHARGE_LIMIT_KEY = 0x05
    const val ENERGY_STORAGE_KEY = 0x06
    const val POWER_SWITCH_KEY = 0x0D
    const val DISPLAY_KEY = 0x0C
    const val RESERVE_MARGIN = 5

    private fun u16(b: ByteArray, i: Int) =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
            ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)

    private fun keyedBody(p: ByteArray): ByteArray = when {
        p.size >= 16 && (p[2].toInt() and 0xFF) == 0x10 && (p[3].toInt() and 0xFF) == 0x00 ->
            p.copyOfRange(16, p.size)
        p.size >= 20 && (p[6].toInt() and 0xFF) == 0x10 && (p[7].toInt() and 0xFF) == 0x00 ->
            p.copyOfRange(20, p.size)
        else -> p
    }

    fun parseKeyedValues(payload: ByteArray): Map<Int, ByteArray> {
        val body = keyedBody(payload)
        val out = HashMap<Int, ByteArray>()
        var off = 0
        while (off + 4 <= body.size) {
            val key = body[off].toInt() and 0xFF
            val marker = body[off + 1].toInt() and 0xFF
            val len = u16(body, off + 2)
            val end = off + 4 + len
            if (marker != 0x10 || end > body.size) break
            out[key] = body.copyOfRange(off + 4, end)
            off = end
        }
        return out
    }

    fun parseChargeLimits(v: ByteArray): ChargeLimits? {
        if (v.size != 24) return null
        val x = IntArray(6) { u32(v, it * 4) }
        val cl = ChargeLimits(
            discharge = x[5], dischargeMin = x[4], dischargeMax = x[3],
            recharge = x[2], rechargeMin = x[1], rechargeMax = x[0],
        )
        if (cl.dischargeMax !in 1..100 || cl.rechargeMax !in 1..100) return null
        if (cl.discharge >= cl.recharge) return null
        return cl
    }

    fun parseReserve(v: ByteArray): Reserve? {
        if (v.size < 4) return null
        return Reserve(
            available = (v[0].toInt() and 0xFF) == 1,
            enabled = (v[1].toInt() and 0xFF) == 1,
            percent = u16(v, 2),
        )
    }

    /** 0x0C holds device/screen/AC-output timeouts (u32 s); AC output is at [8:12]. 0 = never. */
    fun parseAcTimeout(v: ByteArray): Int? = if (v.size >= 12) u32(v, 8) else null

    fun switchLabel(type: Int, seq: Int): String {
        val base = when (type) {
            1 -> "Power"; 2 -> "AC"; 3 -> "USB-A"; 4 -> "USB-C"
            5 -> "SDC"; 6 -> "SDC Lite"; 7 -> "Car"; 8 -> "XT60"
            else -> "Output $type"
        }
        return if (seq > 1) "$base $seq" else base
    }

    /** Parse the 0x0D snapshot into toggleable outputs (rows are [type, seq, sw]). */
    fun parsePortSwitches(value: ByteArray): List<PortSwitch> =
        Report.parseTlvs(value).mapNotNull { tlv ->
            val b = tlv.value
            if (b.size < 3) return@mapNotNull null
            val sw = b[2].toInt() and 0xFF
            if (sw != 1 && sw != 2) return@mapNotNull null
            val type = b[0].toInt() and 0xFF
            val seq = b[1].toInt() and 0xFF
            PortSwitch(type, seq, sw == 1, switchLabel(type, seq))
        }
}
