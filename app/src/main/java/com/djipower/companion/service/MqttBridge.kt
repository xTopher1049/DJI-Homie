package com.djipower.companion.service

import android.content.Context
import com.djipower.companion.data.AppSettings
import com.djipower.companion.data.Conn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

/**
 * Optional bridge to a local MQTT broker (Mosquitto / Home Assistant). Publishes
 * telemetry to <base>/state (retained JSON) and accepts AC commands on
 * <base>/ac/set ("on"/"off"/"1"/"0"), so a spare phone can act as a BLE-to-Wi-Fi
 * gateway. All actions require a live station connection (best-effort).
 */
class MqttBridge(private val context: Context) {
    private var client: MqttAsyncClient? = null

    private fun base(): String =
        AppSettings(context).mqttBaseTopic.ifBlank { "dji_power" }

    fun start() {
        stop()
        val s = AppSettings(context)
        if (!s.mqttEnabled) return
        val host = s.mqttHost.ifBlank { return }
        val uri = "tcp://$host:${s.mqttPort}"
        val c = MqttAsyncClient(uri, "dji-homie-${System.currentTimeMillis()}", MemoryPersistence())
        c.setCallback(object : MqttCallback {
            override fun connectionLost(cause: Throwable?) {}
            override fun messageArrived(topic: String, message: MqttMessage) {
                if (topic == "${base()}/ac/set") {
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
            if (s.mqttUser.isNotBlank()) {
                userName = s.mqttUser
                password = s.mqttPass.toCharArray()
            }
        }
        runCatching {
            c.connect(opts, null, object : IMqttActionListener {
                override fun onSuccess(token: IMqttToken?) {
                    runCatching { c.subscribe("${base()}/ac/set", 0) }
                }
                override fun onFailure(token: IMqttToken?, exception: Throwable?) {}
            })
        }
        client = c
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
        runCatching { client?.disconnectForcibly(500) }
        runCatching { client?.close() }
        client = null
    }
}
