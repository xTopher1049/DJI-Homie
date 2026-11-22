package com.djipower.companion.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DumlTest {
    private fun packet(seq: Int = 0x1234, payload: ByteArray = byteArrayOf(1, 2, 3)) =
        Duml.Packet(Duml.APP_SOURCE, Duml.POWER_DESTINATION, seq, 0x20, Duml.POWER_COMMAND_SET, 0x60, payload)

    @Test fun encodeDecodeRoundTrip() {
        val p = packet()
        val back = Duml.Packet.decode(p.encode())
        assertEquals(p.sequence, back.sequence)
        assertEquals(p.commandId, back.commandId)
        assertEquals(p.flags, back.flags)
        assertArrayEquals(p.payload, back.payload)
    }

    @Test(expected = Duml.ProtocolException::class)
    fun corruptedPayloadFailsCrc16() {
        val bytes = packet().encode()
        bytes[bytes.size - 3] = (bytes[bytes.size - 3] + 1).toByte()
        Duml.Packet.decode(bytes)
    }

    @Test(expected = Duml.ProtocolException::class)
    fun corruptedHeaderFailsCrc8() {
        val bytes = packet().encode()
        bytes[3] = (bytes[3] + 1).toByte()
        Duml.Packet.decode(bytes)
    }

    @Test fun aesRoundTrip() {
        val plain = ByteArray(37) { it.toByte() }
        val enc = Duml.encryptPower1000(plain)
        assertEquals(0, enc.size % 16)
        assertArrayEquals(plain, Duml.decryptPower1000(enc))
    }

    @Test(expected = Duml.ProtocolException::class)
    fun aesRejectsPartialBlock() {
        Duml.decryptPower1000(ByteArray(15))
    }

    @Test fun streamReassemblesFragments() {
        val bytes = packet().encode()
        val s = Duml.Stream()
        assertTrue(s.feed(bytes.copyOfRange(0, 5)).isEmpty())
        assertTrue(s.feed(bytes.copyOfRange(5, 9)).isEmpty())
        assertEquals(1, s.feed(bytes.copyOfRange(9, bytes.size)).size)
    }

    @Test fun streamSkipsLeadingGarbage() {
        val s = Duml.Stream()
        val out = s.feed(byteArrayOf(0x00, 0x13, 0x37) + packet().encode())
        assertEquals(1, out.size)
    }

    @Test fun streamIgnoresFalseHeaderWithBadCrc8() {
        // 0x55 + a plausible max length but wrong header CRC: must not swallow the next frame.
        val s = Duml.Stream()
        val noise = byteArrayOf(0x55, 0xFF.toByte(), 0x03, 0x00)
        val out = s.feed(noise + packet().encode())
        assertEquals(1, out.size)
    }

    @Test fun streamClearDropsStaleHalfFrame() {
        val s = Duml.Stream()
        val first = packet(seq = 1).encode()
        s.feed(first.copyOfRange(0, 8))
        s.clear()
        val out = s.feed(packet(seq = 2).encode())
        assertEquals(1, out.size)
        assertEquals(2, out[0].sequence)
    }

    @Test fun pairKeyIsAsciiNotHexDecoded() {
        val key = Duml.normalizePairKey(" 0123456789ABCDEF0123456789abcdef ")
        assertEquals(32, key.size)
        assertEquals('0'.code.toByte(), key[0])
        assertEquals('a'.code.toByte(), key[10])
    }

    @Test(expected = Duml.ProtocolException::class)
    fun pairKeyRejectsWrongLength() {
        Duml.normalizePairKey("abcd")
    }
}

class EnergyTest {
    private fun le32(vararg v: Int) = v.flatMap { x -> (0 until 4).map { ((x shr (8 * it)) and 0xFF).toByte() } }
        .toByteArray()

    @Test fun parsesKeyedValuesWithAndWithoutHeader() {
        val body = byteArrayOf(0x05, 0x10, 2, 0, 9, 8, 0x06, 0x10, 1, 0, 7)
        val bare = Energy.parseKeyedValues(body)
        assertArrayEquals(byteArrayOf(9, 8), bare[0x05])
        assertArrayEquals(byteArrayOf(7), bare[0x06])
        val header = ByteArray(16).also { it[2] = 0x10 }
        assertEquals(bare.keys, Energy.parseKeyedValues(header + body).keys)
    }

    @Test fun parsesChargeLimits() {
        // rechargeMax, rechargeMin, recharge, dischargeMax, dischargeMin, discharge
        val cl = Energy.parseChargeLimits(le32(100, 50, 90, 50, 0, 10))
        assertNotNull(cl)
        assertEquals(90, cl!!.recharge)
        assertEquals(10, cl.discharge)
        assertEquals(100, cl.rechargeMax)
    }

    @Test fun rejectsBadChargeLimits() {
        assertNull(Energy.parseChargeLimits(ByteArray(20)))
        assertNull(Energy.parseChargeLimits(le32(100, 50, 10, 50, 0, 20))) // discharge >= recharge
    }

    @Test fun energySaverModeRoundTrip() {
        val eco = ByteArray(86).also { it[0] = 1; it[1] = 1 }
        assertEquals("Disabled", Energy.parseEnergySaver(eco).second)
        val sched = Energy.withEnergySaverMode(eco, "Scheduled")
        assertEquals("Scheduled", Energy.parseEnergySaver(sched).second)
        assertEquals(listOf("Disabled", "Scheduled"), Energy.energySaverOptions(sched))
    }

    @Test fun parsesPortSwitches() {
        val v = byteArrayOf(0x0D, 0x00, 3, 0, 2, 1, 1, 0x0D, 0x00, 3, 0, 4, 2, 2)
        val sw = Energy.parsePortSwitches(v)
        assertEquals(2, sw.size)
        assertEquals("AC", sw[0].label)
        assertTrue(sw[0].on)
        assertEquals("USB-C 2", sw[1].label)
    }
}

class AdvancedTest {
    @Test fun timePeriodsRoundTrip() {
        val periods = listOf(
            TimePeriod(peak = true, days = (0..6).toSet(), start = 17 * 60, end = 21 * 60),
            TimePeriod(peak = false, days = setOf(5, 6), start = 23 * 60, end = 6 * 60 + 30),
        )
        val back = Advanced.parseTimePeriods(Advanced.encodeTimePeriods(periods))
        assertEquals(Advanced.sortPeriods(periods), back)
    }

    @Test fun parsesRulesBitmask() {
        // count=8, mask=0b00100001 -> rules 0 and 5
        val text = "080021".toByteArray(Charsets.US_ASCII)
        val v = byteArrayOf(text.size.toByte(), 0) + text
        assertEquals(listOf(0, 5), Advanced.parseRules(v))
        assertEquals(true, Advanced.autoThreshold(Advanced.parseRules(v)))
    }
}
