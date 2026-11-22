package com.djipower.companion.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.djipower.companion.protocol.Models

/**
 * Scans for nearby DJI Power stations so onboarding can capture the BLE MAC.
 * The cloud device list gives the pair key but not the MAC, so we discover it
 * here (same as the DJI app does).
 */
@SuppressLint("MissingPermission")
class DeviceScanner(private val context: Context) {

    data class Found(val name: String, val address: String, val model: String?)

    private var callback: ScanCallback? = null
    private val seen = LinkedHashMap<String, Found>()

    /** Scan for [durationMs]; [onUpdate] fires as matches arrive. */
    fun start(durationMs: Long = 10_000, onUpdate: (List<Found>) -> Unit) {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val scanner = mgr.adapter?.bluetoothLeScanner ?: return
        seen.clear()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name ?: return
                // DJI Power stations advertise names like "DJI Power 1000 ...".
                if (!name.contains("DJI", ignoreCase = true) &&
                    !name.contains("Power", ignoreCase = true)) return
                val addr = result.device.address
                val mfr = result.scanRecord?.getManufacturerSpecificData(Models.MANUFACTURER_ID)
                val model = Models.fromManufacturerData(mfr) ?: Models.fromName(name)
                if (seen.put(addr, Found(name, addr, model)) == null) onUpdate(seen.values.toList())
            }
        }
        callback = cb
        scanner.startScan(null, ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        Handler(Looper.getMainLooper()).postDelayed({ stop() }, durationMs)
    }

    fun stop() {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        callback?.let { mgr.adapter?.bluetoothLeScanner?.stopScan(it) }
        callback = null
    }
}
