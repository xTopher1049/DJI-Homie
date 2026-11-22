package com.djipower.companion.protocol

/** DJI Power station model identification from BLE advertising data. */
object Models {
    const val DEFAULT = "DJI Power 1000"

    /** DJI's Bluetooth company identifier (manufacturer-specific data key). */
    const val MANUFACTURER_ID = 0x08AA

    private val NAMES = mapOf(
        0x91 to "DJI Power 1000",
        0x97 to "DJI Power 1000 V2",
        0x98 to "DJI Power 1000 Mini",
        0x94 to "DJI Power 2000",
        0x9E to "DJI Power Auro 2000 Elite",
    )

    private val NAME_PREFIXES = mapOf("Power1000Mini-" to "DJI Power 1000 Mini")

    /** Model from DJI manufacturer bytes (Android has already stripped the company id). */
    fun fromManufacturerData(bytes: ByteArray?): String? {
        if (bytes == null || bytes.isEmpty()) return null
        val code = bytes[0].toInt() and 0xFF
        return NAMES[code] ?: "DJI Power (0x%02X)".format(code)
    }

    /** Fallback for scanners that drop manufacturer data but keep the name. */
    fun fromName(name: String?): String? {
        if (name == null) return null
        for ((prefix, model) in NAME_PREFIXES) if (name.startsWith(prefix)) return model
        return null
    }

    /** Only the original Power 1000 (0x91) uses AES-wrapped transport. */
    fun isEncrypted(model: String?): Boolean = model == "DJI Power 1000"
}
