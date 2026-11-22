package com.djipower.companion.protocol

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * DJI Power BLE (DUML) protocol codec — Kotlin port of the zuyan9/ha-dji-power-ble
 * `duml.py` reference implementation. No Android dependencies here; this is the
 * pure, testable protocol boundary.
 *
 * Frame: 0x55 | len&0xFF | (ver<<2)|(len>>8) | crc8(first3) | src | dst |
 *        seq(LE16) | flags | cmdSet | cmdId | payload | crc16(LE16)
 */
object Duml {
    // GATT layout (matches the UUIDs recovered from the DJI Home app).
    const val SERVICE_UUID = "0000a002-0000-1000-8000-00805f9b34fb"
    const val WRITE_UUID = "0000c304-0000-1000-8000-00805f9b34fb"
    const val NOTIFY_UUID = "0000c305-0000-1000-8000-00805f9b34fb"
    // Fallback layout seen on some units.
    const val SERVICE_UUID_ALT = "0000fff0-0000-1000-8000-00805f9b34fb"
    const val NOTIFY_UUID_ALT = "0000fff4-0000-1000-8000-00805f9b34fb"
    const val WRITE_UUID_ALT = "0000fff5-0000-1000-8000-00805f9b34fb"

    const val APP_SOURCE = 0x02
    const val POWER_DESTINATION = 0xAB
    const val POWER_COMMAND_SET = 0x5A

    const val DUML_ENCRYPTION_MASK = 0x0F
    const val POWER_1000_ENCRYPTION_TYPE = 0x06

    // Commands
    const val REPORT_COMMAND = 0x61
    const val GET_COMMAND = 0x60
    const val SET_COMMAND = 0x63
    const val HMS_COMMAND = 0x66
    const val AUTH_COMMAND = 0x6A

    // Auth sub-commands
    const val START_BIND = 0x00
    const val CHECK_SECRET_KEY = 0x01

    // Fixed original Power 1000 transport params, independent of the pair key.
    private val POWER_1000_TRANSPORT_KEY = hexToBytes(
        "c2ffbc72909d78a160082c3c256e28365899c3d374fc3e6078ea7fbf533fdf29"
    )
    private val POWER_1000_TRANSPORT_IV = hexToBytes("bf85b37e2b370a4ab754768d04e6e3d6")

    class ProtocolException(message: String) : Exception(message)

    // ---- CRC ----------------------------------------------------------------

    fun crc8(data: ByteArray, initial: Int = 0x77): Int {
        var value = initial
        for (b in data) {
            value = value xor (b.toInt() and 0xFF)
            repeat(8) {
                value = if (value and 1 != 0) (value ushr 1) xor 0x8C else value ushr 1
            }
        }
        return value and 0xFF
    }

    fun crc16(data: ByteArray, initial: Int = 0x3692): Int {
        var value = initial
        for (b in data) {
            value = value xor (b.toInt() and 0xFF)
            repeat(8) {
                value = if (value and 1 != 0) (value ushr 1) xor 0x8408 else value ushr 1
            }
        }
        return value and 0xFFFF
    }

    // ---- AES-256-CBC transport (Power 1000 only) ----------------------------

    fun encryptPower1000(payload: ByteArray): ByteArray {
        if (payload.isEmpty()) throw ProtocolException("cannot encrypt an empty payload")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(POWER_1000_TRANSPORT_KEY, "AES"),
            IvParameterSpec(POWER_1000_TRANSPORT_IV),
        )
        return cipher.doFinal(payload)
    }

    fun decryptPower1000(payload: ByteArray): ByteArray {
        if (payload.isEmpty() || payload.size % 16 != 0) {
            throw ProtocolException("encrypted payload must contain complete AES blocks")
        }
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(POWER_1000_TRANSPORT_KEY, "AES"),
                IvParameterSpec(POWER_1000_TRANSPORT_IV),
            )
            cipher.doFinal(payload)
        } catch (e: Exception) {
            throw ProtocolException("invalid encrypted payload padding")
        }
    }

    // ---- Packet -------------------------------------------------------------

    data class Packet(
        val source: Int,
        val destination: Int,
        val sequence: Int,
        val flags: Int,
        val commandSet: Int,
        val commandId: Int,
        val payload: ByteArray = ByteArray(0),
        val version: Int = 1,
    ) {
        val encryptionType: Int get() = flags and DUML_ENCRYPTION_MASK
        val isResponse: Boolean get() = (flags and 0x80) != 0

        fun encode(): ByteArray {
            val body = ByteArray(7 + payload.size)
            body[0] = source.toByte()
            body[1] = destination.toByte()
            body[2] = (sequence and 0xFF).toByte()
            body[3] = ((sequence ushr 8) and 0xFF).toByte()
            body[4] = flags.toByte()
            body[5] = commandSet.toByte()
            body[6] = commandId.toByte()
            payload.copyInto(body, 7)
            val length = body.size + 6
            if (length > 0x03FF) throw ProtocolException("packet exceeds DUML length field")
            val prefix = byteArrayOf(
                0x55,
                (length and 0xFF).toByte(),
                (((version shl 2) or (length ushr 8)) and 0xFF).toByte(),
            )
            val withCrc8 = prefix + byteArrayOf(crc8(prefix).toByte())
            val packet = withCrc8 + body
            val c = crc16(packet)
            return packet + byteArrayOf((c and 0xFF).toByte(), ((c ushr 8) and 0xFF).toByte())
        }

        companion object {
            fun decode(packet: ByteArray): Packet {
                if (packet.size < 13) throw ProtocolException("packet is too short")
                if (packet[0].toInt() and 0xFF != 0x55) throw ProtocolException("bad magic")
                val declared = (packet[1].toInt() and 0xFF) or ((packet[2].toInt() and 0x03) shl 8)
                if (declared != packet.size) throw ProtocolException("declared length mismatch")
                if ((packet[3].toInt() and 0xFF) != crc8(packet.copyOfRange(0, 3))) {
                    throw ProtocolException("bad header CRC8")
                }
                val calc = crc16(packet.copyOfRange(0, packet.size - 2))
                val got = (packet[packet.size - 2].toInt() and 0xFF) or
                    ((packet[packet.size - 1].toInt() and 0xFF) shl 8)
                if (calc != got) throw ProtocolException("bad packet CRC16")
                return Packet(
                    source = packet[4].toInt() and 0xFF,
                    destination = packet[5].toInt() and 0xFF,
                    sequence = (packet[6].toInt() and 0xFF) or ((packet[7].toInt() and 0xFF) shl 8),
                    flags = packet[8].toInt() and 0xFF,
                    commandSet = packet[9].toInt() and 0xFF,
                    commandId = packet[10].toInt() and 0xFF,
                    payload = packet.copyOfRange(11, packet.size - 2),
                    version = (packet[2].toInt() and 0xFF) ushr 2,
                )
            }
        }
    }

    /** Reassembles DUML frames from fragmented GATT notifications. */
    class Stream {
        private val buffer = ArrayList<Byte>()

        @Synchronized
        fun clear() = buffer.clear()

        @Synchronized
        fun feed(data: ByteArray): List<Packet> {
            for (b in data) buffer.add(b)
            return extract()
        }

        private fun extract(): List<Packet> {
            val out = ArrayList<Packet>()
            while (buffer.isNotEmpty()) {
                val start = buffer.indexOfFirst { it.toInt() and 0xFF == 0x55 }
                if (start < 0) { buffer.clear(); break }
                if (start > 0) repeat(start) { buffer.removeAt(0) }
                if (buffer.size < 4) break
                val length = (buffer[1].toInt() and 0xFF) or ((buffer[2].toInt() and 0x03) shl 8)
                if (length < 13 || length > 0x03FF) { buffer.removeAt(0); continue }
                // Validate the header CRC before waiting for a full frame, so a stray
                // 0x55 in noise can't swallow the real frames that follow it.
                val header = byteArrayOf(buffer[0], buffer[1], buffer[2])
                if ((buffer[3].toInt() and 0xFF) != crc8(header)) { buffer.removeAt(0); continue }
                if (buffer.size < length) break
                val frame = ByteArray(length) { buffer[it] }
                repeat(length) { buffer.removeAt(0) }
                try {
                    out.add(Packet.decode(frame))
                } catch (e: ProtocolException) {
                    // Drop the bad frame and keep scanning.
                }
            }
            return out
        }
    }

    // ---- Pair key -----------------------------------------------------------

    /** The 32 ASCII-hex chars, lowercased, as bytes (NOT hex-decoded). This is
     *  exactly what the auth handshake feeds to the station. */
    fun normalizePairKey(value: String): ByteArray {
        val key = value.trim().lowercase()
        if (key.length != 32 || !key.all { it in "0123456789abcdef" }) {
            throw ProtocolException("pair key must be exactly 32 hex characters")
        }
        return key.toByteArray(Charsets.US_ASCII)
    }

    // ---- Helpers ------------------------------------------------------------

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "")
        return ByteArray(clean.length / 2) {
            ((Character.digit(clean[it * 2], 16) shl 4) +
                Character.digit(clean[it * 2 + 1], 16)).toByte()
        }
    }

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
