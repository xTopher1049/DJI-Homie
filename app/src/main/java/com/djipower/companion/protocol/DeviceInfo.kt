package com.djipower.companion.protocol

data class Accessory(val type: Int, val firmware: String?)

data class DeviceInfo(
    val firmware: String? = null,
    val dongleFirmware: String? = null,
    val cycleCount: Int? = null,
    val maintenanceCharging: Boolean? = null,
    val cloudConnected: Boolean? = null,
    val accessories: List<Accessory> = emptyList(),
    val deviceTimeoutS: Int? = null,
    val screenTimeoutS: Int? = null,
    val acTimeoutS: Int? = null,
    val tempUnitFahrenheit: Boolean? = null,
)

object DeviceInfoParser {
    const val BASE_KEY = 0x00
    const val NETWORK_KEY = 0x02
    const val ACCESSORIES_KEY = 0x04
    const val DISPLAY_KEY = 0x0C

    private fun u16(b: ByteArray, i: Int) =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
            ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)

    private fun ascii(b: ByteArray, start: Int, end: Int): String? {
        if (end > b.size || start >= end) return null
        val slice = b.copyOfRange(start, end)
        val nul = slice.indexOf(0).let { if (it >= 0) it else slice.size }
        val str = String(slice, 0, nul, Charsets.US_ASCII).trim()
        return if (str.isEmpty() || str.any { it.code < 32 || it.code > 126 }) null else str
    }

    fun parse(payload: ByteArray): DeviceInfo {
        val keyed = Energy.parseKeyedValues(payload)
        var di = DeviceInfo()
        keyed[BASE_KEY]?.let { base ->
            di = di.copy(
                firmware = if (base.size >= 23) ascii(base, 7, 23) else null,
                dongleFirmware = if (base.size >= 40) ascii(base, 24, 40) else null,
                cycleCount = if (base.size >= 46) u16(base, 44) else null,
                maintenanceCharging = if (base.size >= 53)
                    when (base[52].toInt() and 0xFF) { 1 -> false; 2 -> true; else -> null } else null,
            )
        }
        keyed[NETWORK_KEY]?.let { if (it.isNotEmpty()) di = di.copy(cloudConnected = it[0].toInt() != 0) }
        keyed[ACCESSORIES_KEY]?.let { di = di.copy(accessories = parseAccessories(it)) }
        keyed[DISPLAY_KEY]?.let { d ->
            di = di.copy(
                deviceTimeoutS = if (d.size >= 4) u32(d, 0) else null,
                screenTimeoutS = if (d.size >= 8) u32(d, 4) else null,
                acTimeoutS = if (d.size >= 12) u32(d, 8) else null,
                tempUnitFahrenheit = if (d.size >= 13) (d[12].toInt() and 0xFF) == 1 else null,
            )
        }
        return di
    }

    private fun parseAccessories(value: ByteArray): List<Accessory> =
        Report.parseTlvs(value).mapNotNull { tlv ->
            val row = tlv.value
            if (row.size < 33) null else Accessory(row[16].toInt() and 0xFF, ascii(row, 17, 33))
        }
}
