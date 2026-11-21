package com.djipower.companion.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.djipower.companion.protocol.Duml
import com.djipower.companion.protocol.Energy
import com.djipower.companion.protocol.EnergyConfig
import com.djipower.companion.protocol.PortSwitch
import com.djipower.companion.protocol.PowerState
import com.djipower.companion.protocol.Report
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/**
 * One authenticated DJI Power BLE session for an original Power 1000.
 *
 * Usage:  val dev = DjiPowerDevice(ctx, mac, pairKey, onState = { ... })
 *         dev.connect()
 *
 * Transport is AES-wrapped (model "DJI Power 1000"). Responses are matched by
 * DUML sequence number; 0x61 pushes are delivered to [onState].
 */
@SuppressLint("MissingPermission")
class DjiPowerDevice(
    private val context: Context,
    private val address: String,
    pairKeyHex: String,
    private val encryptedTransport: Boolean = true, // true for the original Power 1000
    private val onState: (PowerState) -> Unit = {},
    private val onEvent: (String) -> Unit = {},
    private val onConfig: (EnergyConfig) -> Unit = {},
    private val onSwitches: (List<PortSwitch>) -> Unit = {},
    private val onRssi: (Int) -> Unit = {},
) {
    private val pairKey = Duml.normalizePairKey(pairKeyHex)
    private val stream = Duml.Stream()
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var sequence = 0
    private val pending = HashMap<Int, CompletableDeferred<Duml.Packet>>()
    private val rawConfig = HashMap<Int, ByteArray>()
    private val setRules = 0x0E to Duml.hexToBytes("0c001e00efffff3f")
    private var switchList: List<PortSwitch> = emptyList()
    private val rssiHandler = Handler(Looper.getMainLooper())
    private val rssiRunnable = object : Runnable {
        override fun run() {
            gatt?.readRemoteRssi()
            rssiHandler.postDelayed(this, 4000)
        }
    }
    @Volatile var authenticated = false; private set

    private fun nextSeq(): Int { sequence = (sequence + 1) and 0xFFFF; return sequence }

    fun connect() {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val device = mgr.adapter.getRemoteDevice(address)
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        authenticated = false
        rssiHandler.removeCallbacks(rssiRunnable)
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onEvent("Connected; discovering services")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                authenticated = false
                rssiHandler.removeCallbacks(rssiRunnable)
                onEvent("Disconnected")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val layouts = listOf(
                Triple(Duml.SERVICE_UUID, Duml.NOTIFY_UUID, Duml.WRITE_UUID),
                Triple(Duml.SERVICE_UUID_ALT, Duml.NOTIFY_UUID_ALT, Duml.WRITE_UUID_ALT),
            )
            for ((svc, notify, write) in layouts) {
                val service = g.getService(UUID.fromString(svc)) ?: continue
                val n = service.getCharacteristic(UUID.fromString(notify)) ?: continue
                val w = service.getCharacteristic(UUID.fromString(write)) ?: continue
                writeChar = w
                g.setCharacteristicNotification(n, true)
                val cccd = n.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                cccd?.let {
                    it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(it)
                }
                onEvent("GATT ready ($svc); authenticating")
                return
            }
            onEvent("No supported DJI Power GATT layout found")
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: android.bluetooth.BluetoothGattDescriptor, status: Int) {
            // Notifications are live; kick off auth on a worker thread.
            Thread { try { runAuth() } catch (e: Exception) { onEvent("Auth failed: ${e.message}") } }.start()
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            handleIncoming(c.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            handleIncoming(value)
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) onRssi(rssi)
        }
    }

    private fun handleIncoming(data: ByteArray) {
        for (packet in stream.feed(data)) {
            val payload = try { decode(packet) } catch (e: Exception) { continue }
            val waiter = pending.remove(packet.sequence)
            if (waiter != null) { waiter.complete(packet); continue }
            if (packet.commandId == Duml.REPORT_COMMAND) {
                try {
                    val st = Report.parseReport(payload)
                    onState(st)
                    if (switchList.isNotEmpty() && st.ports.isNotEmpty()) {
                        val byId = st.ports.associateBy { it.type to it.seq }
                        val updated = switchList.map { sw ->
                            byId[sw.type to sw.seq]?.let { sw.copy(on = it.on) } ?: sw
                        }
                        if (updated != switchList) {
                            switchList = updated
                            onSwitches(updated)
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun decode(packet: Duml.Packet): ByteArray =
        when {
            packet.encryptionType == 0 -> packet.payload
            packet.encryptionType == Duml.POWER_1000_ENCRYPTION_TYPE && encryptedTransport ->
                Duml.decryptPower1000(packet.payload)
            else -> throw Duml.ProtocolException("unsupported encryption ${packet.encryptionType}")
        }

    /** Send a command and await the matching response (blocking helper). */
    private suspend fun request(commandId: Int, payload: ByteArray, timeoutMs: Long = 8000): Duml.Packet {
        val g = gatt ?: throw IllegalStateException("not connected")
        val w = writeChar ?: throw IllegalStateException("GATT not ready")
        val seq = nextSeq()
        var flags = 0x20
        var body = payload
        if (encryptedTransport) { body = Duml.encryptPower1000(payload); flags = flags or Duml.POWER_1000_ENCRYPTION_TYPE }
        val packet = Duml.Packet(Duml.APP_SOURCE, Duml.POWER_DESTINATION, seq, flags, Duml.POWER_COMMAND_SET, commandId, body)
        val deferred = CompletableDeferred<Duml.Packet>()
        pending[seq] = deferred
        @Suppress("DEPRECATION")
        run {
            w.value = packet.encode()
            w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            g.writeCharacteristic(w)
        }
        return try { withTimeout(timeoutMs) { deferred.await() } }
        finally { pending.remove(seq) }
    }

    private fun runAuth() {
        kotlinx.coroutines.runBlocking {
            repeat(2) { attempt ->
                val challenge = decode(request(Duml.AUTH_COMMAND, byteArrayOf(Duml.START_BIND.toByte())))
                if (challenge.isEmpty() || challenge[0].toInt() != 0x00 || challenge.size < 5)
                    throw Duml.ProtocolException("invalid auth challenge")
                val nonce = challenge.copyOfRange(1, 5)
                val material = byteArrayOf(Duml.CHECK_SECRET_KEY.toByte()) + nonce + pairKey + byteArrayOf(0x00)
                val result = decode(request(Duml.AUTH_COMMAND, material))
                when (result.getOrNull(0)?.toInt() ?: -1) {
                    0x00 -> {
                        authenticated = true; onEvent("Locally authenticated")
                        runCatching { readConfig() }
                        rssiHandler.post(rssiRunnable)
                        return@runBlocking
                    }
                    0x03 -> if (attempt == 0) return@repeat  // retry once with a fresh challenge
                }
                throw Duml.ProtocolException("station rejected authentication; if the key is right, power-cycle it")
            }
        }
    }


    /** Toggle the AC main output. */
    suspend fun setAcOutput(enabled: Boolean) {
        val state = if (enabled) 0x01 else 0x02
        val switchValue = byteArrayOf(0x0D, 0x00, 0x03, 0x00, 0x02, 0x01, state.toByte())
        val rules = Duml.hexToBytes("0c001e00efffff3f")
        val payload = buildKeyedSet(listOf(0x0D to switchValue, 0x0E to rules))
        request(Duml.SET_COMMAND, payload)
    }

    private fun buildKeyedSet(entries: List<Pair<Int, ByteArray>>): ByteArray {
        val ts = System.currentTimeMillis()
        val header = ByteArray(16)
        header[2] = 0x10
        for (i in 0 until 8) header[4 + i] = ((ts shr (8 * i)) and 0xFF).toByte()
        val out = ArrayList<Byte>()
        header.forEach { out.add(it) }
        for ((key, value) in entries) {
            out.add(key.toByte()); out.add(0x10)
            out.add((value.size and 0xFF).toByte()); out.add(((value.size shr 8) and 0xFF).toByte())
            value.forEach { out.add(it) }
        }
        return out.toByteArray()
    }

    // ---- Energy Management ---------------------------------------------------

    private fun writeU32(b: ByteArray, i: Int, v: Int) {
        for (k in 0 until 4) b[i + k] = ((v shr (8 * k)) and 0xFF).toByte()
    }

    private fun writeU16(b: ByteArray, i: Int, v: Int) {
        b[i] = (v and 0xFF).toByte(); b[i + 1] = ((v shr 8) and 0xFF).toByte()
    }

    /** GET charge limits (0x05), reserve (0x06), power switches (0x0D); publish. */
    suspend fun readConfig() {
        val resp = request(
            Duml.GET_COMMAND,
            byteArrayOf(0x00, 0x05, 0x10, 0x06, 0x10, 0x0C, 0x10, 0x0D, 0x10),
        )
        val payload = decode(resp)
        val keyed = Energy.parseKeyedValues(payload)
        keyed[Energy.CHARGE_LIMIT_KEY]?.let { rawConfig[Energy.CHARGE_LIMIT_KEY] = it }
        keyed[Energy.ENERGY_STORAGE_KEY]?.let { rawConfig[Energy.ENERGY_STORAGE_KEY] = it }
        keyed[Energy.POWER_SWITCH_KEY]?.let { rawConfig[Energy.POWER_SWITCH_KEY] = it }
        keyed[Energy.DISPLAY_KEY]?.let { rawConfig[Energy.DISPLAY_KEY] = it }

        rawConfig[Energy.POWER_SWITCH_KEY]?.let {
            switchList = Energy.parsePortSwitches(it)
            onSwitches(switchList)
        }

        val limits = rawConfig[Energy.CHARGE_LIMIT_KEY]?.let { Energy.parseChargeLimits(it) } ?: return
        val reserve = rawConfig[Energy.ENERGY_STORAGE_KEY]?.let { Energy.parseReserve(it) }
        val acTimeout = rawConfig[Energy.DISPLAY_KEY]?.let { Energy.parseAcTimeout(it) }
        onConfig(EnergyConfig(limits, reserve, acTimeout))
    }

    /** Toggle one station output on/off, preserving its row body; then re-read. */
    suspend fun setPortSwitch(type: Int, seq: Int, on: Boolean) {
        val snapshot = rawConfig[Energy.POWER_SWITCH_KEY]
            ?: throw IllegalStateException("switch state not loaded yet")
        val row = Report.parseTlvs(snapshot).firstOrNull {
            it.value.size >= 3 &&
                (it.value[0].toInt() and 0xFF) == type &&
                (it.value[1].toInt() and 0xFF) == seq
        } ?: throw IllegalStateException("output not found on this station")
        val body = row.value.copyOf()
        body[2] = (if (on) 1 else 2).toByte()
        val value = byteArrayOf(
            0x0D, 0x00, (body.size and 0xFF).toByte(), ((body.size shr 8) and 0xFF).toByte(),
        ) + body
        request(Duml.SET_COMMAND, buildKeyedSet(listOf(Energy.POWER_SWITCH_KEY to value, setRules)))
        readConfig()
    }

    /** Set discharge/recharge limits, preserving the record's other bytes. */
    suspend fun setChargeLimits(discharge: Int, recharge: Int) {
        val cur = rawConfig[Energy.CHARGE_LIMIT_KEY]
            ?: throw IllegalStateException("charge limits not loaded yet")
        val v = cur.copyOf()
        writeU32(v, 8, recharge)
        writeU32(v, 20, discharge)
        request(Duml.SET_COMMAND, buildKeyedSet(listOf(Energy.CHARGE_LIMIT_KEY to v, setRules)))
        readConfig()
    }

    /** Enable/disable the custom backup reserve and/or set its level. */
    suspend fun setReserve(enabled: Boolean? = null, percent: Int? = null, min: Int = 0, max: Int = 100) {
        val cur = rawConfig[Energy.ENERGY_STORAGE_KEY] ?: return
        if ((cur[0].toInt() and 0xFF) != 1) throw IllegalStateException("no custom reserve on this station")
        val v = cur.copyOf()
        if (enabled != null) v[1] = (if (enabled) 1 else 2).toByte()
        if (percent != null) writeU16(v, 2, percent.coerceIn(min, max))
        request(Duml.SET_COMMAND, buildKeyedSet(listOf(Energy.ENERGY_STORAGE_KEY to v, setRules)))
        readConfig()
    }

    /** UPS mode: AC output stays on regardless of load (timeout 0 = never). */
    suspend fun setAcAlwaysOn(enabled: Boolean) {
        val cur = rawConfig[Energy.DISPLAY_KEY]
            ?: throw IllegalStateException("device settings not loaded yet")
        if (cur.size < 12) throw IllegalStateException("unexpected device-settings size")
        val v = cur.copyOf()
        writeU32(v, 8, if (enabled) 0 else 1800)
        request(Duml.SET_COMMAND, buildKeyedSet(listOf(Energy.DISPLAY_KEY to v, setRules)))
        readConfig()
    }
}
