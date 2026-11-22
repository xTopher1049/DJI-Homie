package com.djipower.companion.service

import android.content.Context
import com.djipower.companion.data.AppSettings
import com.djipower.companion.data.Conn
import com.djipower.companion.data.CredStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONObject

/**
 * Optional bridge to a local MQTT broker (Mosquitto / Home Assistant).
 *   <base>/state         retained JSON telemetry
 *   <base>/availability  retained "online" / "offline" (also the last will)
 *   <base>/ac/set        accepts "on"/"off"/"1"/"0"/"true"/"false"
 * plus retained Home Assistant discovery configs under homeassistant/…, so a
 * "DJI Power" device with sensors and an AC switch appears in HA by itself.
 *
 * Paho's automatic reconnect only kicks in after a first successful connect, so
 * the first connect is retried here with backoff (broker down at boot, Wi-Fi not
 * up yet). The clean session drops subscriptions on every reconnect, so they are
 * re-made in [MqttCallbackExtended.connectComplete]. [status] reports what is
 * happening so the settings screen can show it.
 */
class MqttBridge(private val context: Context) {
    private var client: MqttAsyncClient? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectJob: Job? = null

    /** Settings the running client was built from; [start] is skipped when unchanged. */
    @Volatile var configKey: String? = null
        private set

    private fun base(): String =
        AppSettings(context).mqttBaseTopic.ifBlank { "dji_power" }

    @Synchronized
    fun start() {
        stop()
        val s = AppSettings(context)
        configKey = keyOf(s)
        if (!s.mqttEnabled) { _status.value = "Off"; return }
        val host = s.mqttHost.trim()
        if (host.isEmpty()) { _status.value = "Enter the broker host, then tap Apply"; return }
        val base = base()
        val uri = "tcp://$host:${s.mqttPort}"
        val c = try {
            MqttAsyncClient(uri, "dji-homie-${System.currentTimeMillis()}", MemoryPersistence())
        } catch (e: Exception) {
            _status.value = "Invalid broker address: ${e.message}"
            return
        }
        c.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                runCatching { c.subscribe("$base/ac/set", 0) }
                runCatching { c.publish("$base/availability", "online".toByteArray(), 1, true) }
                runCatching { publishDiscovery(c, base) }
                _status.value = "Connected to $host:${s.mqttPort}"
            }
            override fun connectionLost(cause: Throwable?) {
                _status.value = "Connection lost (${cause?.message ?: "unknown"}); reconnecting"
            }
            override fun messageArrived(topic: String, message: MqttMessage) {
                if (topic == "$base/ac/set") {
                    val on = message.toString().trim().lowercase() in listOf("on", "1", "true")
                    val device = Conn.device ?: return
                    CoroutineScope(Dispatchers.Main).launch {
                        runCatching { device.setAcOutput(on) }
                    }
                }
            }
            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })
        val opts = MqttConnectOptions().apply {
            isAutomaticReconnect = true
            isCleanSession = true
            connectionTimeout = 10
            setWill("$base/availability", "offline".toByteArray(), 1, true)
            if (s.mqttUser.isNotBlank()) {
                userName = s.mqttUser
                password = s.mqttPass.toCharArray()
            }
        }
        client = c
        _status.value = "Connecting to $host:${s.mqttPort}…"
        connectJob = scope.launch {
            var backoff = 5_000L
            while (isActive && !c.isConnected) {
                try {
                    c.connect(opts).waitForCompletion(15_000)
                } catch (e: MqttException) {
                    _status.value = "Can't connect: ${describe(e)} — retrying in ${backoff / 1000}s"
                } catch (e: Exception) {
                    _status.value = "Can't connect: ${e.message} — retrying in ${backoff / 1000}s"
                }
                if (c.isConnected) break
                delay(backoff)
                backoff = minOf(backoff * 2, 120_000L)
            }
        }
    }

    fun publishState(json: String) {
        val c = client ?: return
        if (!c.isConnected) return
        runCatching {
            c.publish("${base()}/state", MqttMessage(json.toByteArray()).apply {
                qos = 0
                isRetained = true
            })
        }
    }

    @Synchronized
    fun stop() {
        connectJob?.cancel()
        connectJob = null
        val c = client ?: return
        client = null
        runCatching {
            if (c.isConnected) {
                c.publish("${base()}/availability", "offline".toByteArray(), 1, true)
                    .waitForCompletion(1_000)
            }
        }
        runCatching { c.disconnectForcibly(500) }
        runCatching { c.close() }
        _status.value = "Off"
    }

    /** Stop and release the bridge's own coroutine scope (service teardown). */
    fun shutdown() {
        stop()
        scope.cancel()
    }

    // ---- Home Assistant MQTT discovery ---------------------------------------

    private fun publishDiscovery(c: MqttAsyncClient, base: String) {
        val node = base.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val creds = CredStore(context)
        val device = JSONObject().apply {
            put("identifiers", JSONArray().put("dji_homie_$node"))
            put("name", creds.stationName?.takeIf { it.isNotBlank() } ?: "DJI Power")
            put("manufacturer", "DJI")
            put("model", creds.model)
        }
        fun common(id: String, name: String) = JSONObject().apply {
            put("name", name)
            put("unique_id", "${node}_$id")
            put("state_topic", "$base/state")
            put("availability_topic", "$base/availability")
            put("device", device)
        }
        fun sensor(id: String, name: String, tpl: String, unit: String?, cls: String?) =
            common(id, name).apply {
                put("value_template", tpl)
                unit?.let { put("unit_of_measurement", it) }
                cls?.let { put("device_class", it) }
                put("state_class", "measurement")
            }

        val configs = listOf(
            "sensor" to sensor("battery", "Battery", "{{ value_json.battery }}", "%", "battery"),
            "sensor" to sensor("output_power", "Output power", "{{ value_json.out_w }}", "W", "power"),
            "sensor" to sensor("input_power", "Input power", "{{ value_json.in_w }}", "W", "power"),
            "sensor" to sensor("ac_power", "AC output power", "{{ value_json.ac_w }}", "W", "power"),
            "sensor" to sensor("dc_power", "DC output power", "{{ value_json.dc_w }}", "W", "power"),
            "sensor" to sensor("runtime", "Runtime", "{{ value_json.runtime_min }}", "min", "duration"),
            "binary_sensor" to common("charging", "Charging").apply {
                put("value_template", "{{ 'ON' if value_json.charging else 'OFF' }}")
                put("device_class", "battery_charging")
            },
            "switch" to common("ac", "AC output").apply {
                put("command_topic", "$base/ac/set")
                put("payload_on", "on")
                put("payload_off", "off")
                // null ac_on (switch state not read yet) renders None -> HA shows unknown.
                put("value_template",
                    "{{ None if value_json.ac_on is none else ('on' if value_json.ac_on else 'off') }}")
                put("state_on", "on")
                put("state_off", "off")
                put("icon", "mdi:power-socket-us")
            },
        )
        for ((component, cfg) in configs) {
            val id = cfg.getString("unique_id")
            c.publish("homeassistant/$component/$id/config", cfg.toString().toByteArray(), 1, true)
        }
    }

    companion object {
        private val _status = MutableStateFlow("Off")
        /** Human-readable bridge state for the settings screen. */
        val status: StateFlow<String> = _status.asStateFlow()

        fun keyOf(s: AppSettings): String =
            listOf(s.mqttEnabled, s.mqttHost.trim(), s.mqttPort, s.mqttUser, s.mqttPass, s.mqttBaseTopic)
                .joinToString("\u0000")

        private fun describe(e: MqttException): String = when (e.reasonCode.toShort()) {
            MqttException.REASON_CODE_NOT_AUTHORIZED,
            MqttException.REASON_CODE_FAILED_AUTHENTICATION -> "wrong username or password"
            MqttException.REASON_CODE_SERVER_CONNECT_ERROR ->
                "broker not reachable (check host, port and that the phone is on the same network)"
            MqttException.REASON_CODE_CLIENT_TIMEOUT -> "timed out"
            else -> e.message ?: "error ${e.reasonCode}"
        }
    }
}
