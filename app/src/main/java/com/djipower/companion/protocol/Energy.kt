package com.djipower.companion.protocol

/** Decoded Energy Management config (0x05 charge limits, 0x06 backup reserve). */
data class ChargeLimits(
    val discharge: Int, val dischargeMin: Int, val dischargeMax: Int,
    val recharge: Int, val rechargeMin: Int, val rechargeMax: Int,
)
data class Reserve(val available: Boolean, val enabled: Boolean, val percent: Int)
data class EnergyConfig(val limits: ChargeLimits, val reserve: Reserve?)

object Energy {
    const val CHARGE_LIMIT_KEY = 0x05
    const val ENERGY_STORAGE_KEY = 0x06
    const val RESERVE_MARGIN = 5

    private fun u16(b: ByteArray, i: Int) =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
            ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)

    /** Strip the keyed-config header (shared with the report parser). */
    private fun keyedBody(p: ByteArray): ByteArray = when {
        p.size >= 16 && (p[2].toInt() and 0xFF) == 0x10 && (p[3].toInt() and 0xFF) == 0x00 ->
            p.copyOfRange(16, p.size)
        p.size >= 20 && (p[6].toInt() and 0xFF) == 0x10 && (p[7].toInt() and 0xFF) == 0x00 ->
            p.copyOfRange(20, p.size)
        else -> p
    }

    /** Parse key:u8, marker=0x10, len:u16, value records. */
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
        // values[0..2] = recharge (max,min,cur); [3..5] = discharge (max,min,cur)
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
            enabled = (v[1].toInt() and 0xFF) == 1,  // 1 = on, 2 = off
            percent = u16(v, 2),
        )
    }
}
