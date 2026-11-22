package com.djipower.companion.service

import android.content.Context
import com.djipower.companion.data.AppSettings
import com.djipower.companion.data.Conn
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
class MqttBridge(
    private val context: Context,
    /** Current telemetry JSON, or null with no station data; sent right after (re)connect. */
    private val snapshot: () -> String? = { null },
) {
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
        if (!s.mqttEnabled) { set("Off", Health.OFF); return }
        val host = s.mqttHost.trim()
        if (host.isEmpty()) { set("Enter the broker host, then tap Apply", Health.ERROR); return }
        val base = base()
        val uri = "tcp://$host:${s.mqttPort}"
        val c = try {
            MqttAsyncClient(uri, "dji-homie-${System.currentTimeMillis()}", MemoryPersistence())
        } catch (e: Exception) {
            set("Invalid broker address: ${e.message}", Health.ERROR)
            return
        }
        c.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                runCatching { c.subscribe(arrayOf("$base/ac/set", "$base/set/+"), intArrayOf(0, 0)) }
                runCatching { c.publish("$base/availability", "online".toByteArray(), 1, true) }
                synchronized(discoveryLock) { published.clear() }
                refreshDiscovery()
                publishSettings()
                // Telemetry that arrived before the connect was dropped, and an idle
                // station sends nothing new, so push the current reading now.
                snapshot()?.let { publishState(it) }
                set("Connected to $host:${s.mqttPort}", Health.CONNECTED)
            }
            override fun connectionLost(cause: Throwable?) {
                set("Connection lost (${cause?.message ?: "unknown"}); reconnecting", Health.CONNECTING)
            }
            override fun messageArrived(topic: String, message: MqttMessage) {
                if (topic == "$base/ac/set") {
                    val on = message.toString().trim().lowercase() in listOf("on", "1", "true")
                    val device = Conn.device ?: return
                    CoroutineScope(Dispatchers.Main).launch {
                        runCatching { device.setAcOutput(on) }
                    }
                } else if (topic.startsWith("$base/set/")) {
                    val id = topic.removePrefix("$base/set/")
                    val payload = message.toString()
                    CoroutineScope(Dispatchers.Main).launch {
                        runCatching { MqttEntities.handle(context, id, payload) }
                            .onFailure { Conn.postStatus("MQTT $id: ${it.message}") }
                        // Echo the real state so HA snaps back after a rejected command.
                        scope.launch { publishSettings() }
                    }
                }
            }
            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })
        val opts = MqttConnectOptions().apply {
            isAutomaticReconnect = true
            isCleanSession = true
            connectionTimeout = 10
            // Discovery sends ~40 retained configs at once; Paho's default window is 10.
            maxInflight = 200
            setWill("$base/availability", "offline".toByteArray(), 1, true)
            if (s.mqttUser.isNotBlank()) {
                userName = s.mqttUser
                password = s.mqttPass.toCharArray()
            }
        }
        client = c
        set("Connecting to $host:${s.mqttPort}…", Health.CONNECTING)
        connectJob = scope.launch {
            var backoff = 5_000L
            while (isActive && !c.isConnected) {
                try {
                    c.connect(opts).waitForCompletion(15_000)
                } catch (e: MqttException) {
                    set("Can't connect: ${describe(e)} — retrying in ${backoff / 1000}s", Health.ERROR)
                } catch (e: Exception) {
                    set("Can't connect: ${e.message} — retrying in ${backoff / 1000}s", Health.ERROR)
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
            _lastSent.value = System.currentTimeMillis()
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
        set("Off", Health.OFF)
    }

    /** Stop and release the bridge's own coroutine scope (service teardown). */
    fun shutdown() {
        stop()
        scope.cancel()
    }

    // ---- Home Assistant MQTT discovery + settings -----------------------------

    private val discoveryLock = Any()
    /** Discovery topics published this session -> payload, to send only changes
     *  and to remove entities (empty retained payload) the station no longer has. */
    private val published = HashMap<String, String>()

    /** Publish discovery for entities that appeared or changed; remove vanished ones. */
    fun refreshDiscovery() {
        val c = client ?: return
        if (!c.isConnected) return
        val next = runCatching { MqttEntities.discovery(context, base()) }.getOrNull() ?: return
        synchronized(discoveryLock) {
            for ((topic, payload) in next) {
                if (published[topic] != payload) {
                    val ok = runCatching { c.publish(topic, payload.toByteArray(), 1, true) }.isSuccess
                    if (ok) published[topic] = payload
                }
            }
            for (gone in published.keys - next.keys) {
                val ok = runCatching { c.publish(gone, ByteArray(0), 1, true) }.isSuccess
                if (ok) published.remove(gone)
            }
        }
    }

    /** Retained JSON every config entity reads its state from. */
    fun publishSettings() {
        val c = client ?: return
        if (!c.isConnected) return
        runCatching {
            c.publish("${base()}/${MqttEntities.SETTINGS}",
                MqttEntities.settingsJson(context).toByteArray(), 0, true)
            _lastSent.value = System.currentTimeMillis()
        }
    }

    /** Station alarm as an HA event (not retained). */
    fun publishAlarm(code: String) {
        val c = client ?: return
        if (!c.isConnected) return
        val json = JSONObject().put("event_type", "alarm").put("code", code).toString()
        runCatching { c.publish("${base()}/${MqttEntities.ALARM}", json.toByteArray(), 0, false) }
    }

    enum class Health { OFF, CONNECTING, CONNECTED, ERROR }

    companion object {
        private val _status = MutableStateFlow("Off")
        /** Human-readable bridge state for the settings screen. */
        val status: StateFlow<String> = _status.asStateFlow()

        private val _health = MutableStateFlow(Health.OFF)
        /** Coarse state for the dashboard indicator's colour. */
        val health: StateFlow<Health> = _health.asStateFlow()

        private val _lastSent = MutableStateFlow(0L)
        /** Wall-clock ms of the last telemetry publish (0 = none yet). */
        val lastSent: StateFlow<Long> = _lastSent.asStateFlow()

        private fun set(text: String, h: Health) {
            _status.value = text
            _health.value = h
        }

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
