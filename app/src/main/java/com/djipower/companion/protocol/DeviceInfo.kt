package com.djipower.companion.protocol

data class Accessory(val type: Int, val firmware: String?) {
    /** Human name for the station's accessory-type code (DJI AccessoryTypeEnum).
     *  2 confirmed = solar MPPT adapter; extend as other codes are identified. */
    val name: String
        get() = when (type) {
            1 -> "Car Power Outlet to SDC Power Cable (12V/24V)" // best guess, code unverified
            2 -> "Solar Panel Adapter Module (MPPT)"             // confirmed
            else -> "Accessory (type $type)"
        }
}

data class ExpansionBattery(
    val seq: Int, val serial: String, val percent: Float?,
    val cycleCount: Int, val ratedWh: Int, val temperatureC: Float?, val firmware: String?,
)

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
    val expansionBatteries: List<ExpansionBattery> = emptyList(),
)

object DeviceInfoParser {
    const val BASE_KEY = 0x00
    const val EXPANSION_KEY = 0x01
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
        keyed[EXPANSION_KEY]?.let { di = di.copy(expansionBatteries = parseExpansion(it)) }
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

    private fun parseExpansion(value: ByteArray): List<ExpansionBattery> =
        Report.parseTlvs(value).filter { it.tag == 0x100F }.mapNotNull { tlv ->
            val row = tlv.value
            if (row.size < 31) return@mapNotNull null
            val capacity = u32(row, 7)
            if (capacity == 0) return@mapNotNull null
            val serial = ascii(row, 15, 31) ?: return@mapNotNull null
            val percent = u16(row, 1) / 100f
            val temp = if (row.size >= 34 && (row[33].toInt() and 0xFF) in intArrayOf(1, 2, 3)) {
                val raw = u16(row, 31); (if (raw >= 0x8000) raw - 0x10000 else raw) / 100f
            } else null
            val fw = if (row.size >= 50) ascii(row, 34, 50) else null
            ExpansionBattery(
                row[0].toInt() and 0xFF, serial, if (percent <= 100f) percent else null,
                u32(row, 11), capacity, temp, fw,
            )
        }

    private fun parseAccessories(value: ByteArray): List<Accessory> =
        Report.parseTlvs(value).mapNotNull { tlv ->
            val row = tlv.value
            if (row.size < 33) null else Accessory(row[16].toInt() and 0xFF, ascii(row, 17, 33))
        }
}
