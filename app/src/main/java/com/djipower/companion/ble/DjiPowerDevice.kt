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
import com.djipower.companion.protocol.Duml
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
) {
    private val pairKey = Duml.normalizePairKey(pairKeyHex)
    private val stream = Duml.Stream()
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var sequence = 0
    private val pending = HashMap<Int, CompletableDeferred<Duml.Packet>>()
    @Volatile var authenticated = false; private set

    private fun nextSeq(): Int { sequence = (sequence + 1) and 0xFFFF; return sequence }

    fun connect() {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val device = mgr.adapter.getRemoteDevice(address)
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        authenticated = false
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
    }

    private fun handleIncoming(data: ByteArray) {
        for (packet in stream.feed(data)) {
            val payload = try { decode(packet) } catch (e: Exception) { continue }
            val waiter = pending.remove(packet.sequence)
            if (waiter != null) { waiter.complete(packet); continue }
            if (packet.commandId == Duml.REPORT_COMMAND) {
                try { onState(Report.parseReport(payload)) } catch (_: Exception) {}
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
                    0x00 -> { authenticated = true; onEvent("Authenticated"); return@runBlocking }
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
}
