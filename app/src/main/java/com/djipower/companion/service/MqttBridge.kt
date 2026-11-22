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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

/**
 * Optional bridge to a local MQTT broker (Mosquitto / Home Assistant).
 *   <base>/state         retained JSON telemetry
 *   <base>/availability  retained "online" / "offline" (also the last will)
 *   <base>/ac/set        accepts "on"/"off"/"1"/"0"/"true"/"false"
 * so a spare phone can act as a BLE-to-Wi-Fi gateway. All actions require a live
 * station connection (best-effort).
 *
 * Paho's automatic reconnect only kicks in after a first successful connect, so
 * the first connect is retried here with backoff (broker down at boot, Wi-Fi not
 * up yet). The clean session drops subscriptions on every reconnect, so they are
 * re-made in [MqttCallbackExtended.connectComplete].
 */
class MqttBridge(private val context: Context) {
    private var client: MqttAsyncClient? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectJob: Job? = null

    private fun base(): String =
        AppSettings(context).mqttBaseTopic.ifBlank { "dji_power" }

    fun start() {
        stop()
        val s = AppSettings(context)
        if (!s.mqttEnabled) return
        val host = s.mqttHost.ifBlank { return }
        val base = base()
        val uri = "tcp://$host:${s.mqttPort}"
        val c = MqttAsyncClient(uri, "dji-homie-${System.currentTimeMillis()}", MemoryPersistence())
        c.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                runCatching { c.subscribe("$base/ac/set", 0) }
                runCatching { c.publish("$base/availability", "online".toByteArray(), 1, true) }
            }
            override fun connectionLost(cause: Throwable?) {}
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
        connectJob = scope.launch {
            var backoff = 5_000L
            while (isActive && !c.isConnected) {
                runCatching { c.connect(opts).waitForCompletion(15_000) }
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
    }

    /** Stop and release the bridge's own coroutine scope (service teardown). */
    fun shutdown() {
        stop()
        scope.cancel()
    }
}
