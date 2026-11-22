package com.djipower.companion.data

import android.content.Context
import com.djipower.companion.ble.DjiPowerDevice
import com.djipower.companion.protocol.DeviceInfo
import com.djipower.companion.protocol.Duml
import com.djipower.companion.protocol.EnergyConfig
import com.djipower.companion.protocol.PortSwitch
import com.djipower.companion.protocol.PowerState
import com.djipower.companion.widget.StatusWidget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide owner of the single BLE connection and its live state.
 *
 * The UI (foreground) and the optional background service both read from here,
 * so the connection is decoupled from any one Activity and can be held alive by
 * the foreground service when a background automation needs it.
 */
object Conn {
    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _state = MutableStateFlow<PowerState?>(null)
    val state: StateFlow<PowerState?> = _state.asStateFlow()

    private val _energy = MutableStateFlow<EnergyConfig?>(null)
    val energy: StateFlow<EnergyConfig?> = _energy.asStateFlow()

    private val _switches = MutableStateFlow<List<PortSwitch>>(emptyList())
    val switches: StateFlow<List<PortSwitch>> = _switches.asStateFlow()

    private val _rssi = MutableStateFlow<Int?>(null)
    val rssi: StateFlow<Int?> = _rssi.asStateFlow()

    private val _deviceInfo = MutableStateFlow<DeviceInfo?>(null)
    val deviceInfo: StateFlow<DeviceInfo?> = _deviceInfo.asStateFlow()

    private val _alarms = MutableStateFlow<List<Pair<Long, String>>>(emptyList())
    val alarms: StateFlow<List<Pair<Long, String>>> = _alarms.asStateFlow()

    /** Bumped whenever a telemetry sample is recorded, so the UI re-reads history. */
    private val _historyRev = MutableStateFlow(0)
    val historyRev: StateFlow<Int> = _historyRev.asStateFlow()

    @Volatile
    var device: DjiPowerDevice? = null
        private set

    private var history: HistoryStore? = null
    private var lastWidgetWrite = 0L

    fun history(context: Context): HistoryStore =
        history ?: HistoryStore(context.applicationContext).also { history = it }

    /** Create and connect the device if not already running. Idempotent. */
    @Synchronized
    fun start(context: Context) {
        if (device != null) return
        val app = context.applicationContext
        val creds = CredStore(app)
        val mac = creds.mac ?: return
        val key = creds.pairKey ?: return
        val hist = history(app)
        _status.value = "Connecting…"
        device = DjiPowerDevice(
            context = app, address = mac, pairKeyHex = key,
            onState = { s ->
                _state.value = s
                if (hist.record(s.batteryPercent?.toFloat(), s.outputW, s.inputW))
                    _historyRev.value = _historyRev.value + 1
                maybeUpdateWidget(app, s)
            },
            onEvent = { e -> _status.value = e },
            onConfig = { c -> _energy.value = c },
            onSwitches = { sw -> _switches.value = sw },
            onRssi = { r -> _rssi.value = r },
            onDeviceInfo = { di -> _deviceInfo.value = di },
            onHms = { b ->
                _alarms.value = (_alarms.value + (System.currentTimeMillis() to Duml.bytesToHex(b)))
                    .takeLast(50)
            },
        ).also { it.connect() }
    }

    private fun maybeUpdateWidget(context: Context, s: PowerState) {
        val t = System.currentTimeMillis()
        if (t - lastWidgetWrite < 10_000) return
        lastWidgetWrite = t
        AppSettings(context).writeWidget(
            s.batteryPercent?.toInt() ?: -1, s.outputW ?: 0, s.inputW ?: 0, s.runtimeMin ?: -1,
        )
        StatusWidget.refresh(context)
    }

    /** Push a transient status line (e.g. a command error) into the status flow. */
    fun postStatus(msg: String) { _status.value = msg }

    /** Tear down the connection and reset live state. */
    @Synchronized
    fun stop() {
        device?.disconnect()
        device = null
        _state.value = null
        _switches.value = emptyList()
        _rssi.value = null
        _status.value = "Idle"
    }
}
