package com.djipower.companion.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.djipower.companion.data.AppSettings
import com.djipower.companion.data.Conn
import com.djipower.companion.protocol.PowerState
import com.djipower.companion.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import org.json.JSONObject
import kotlinx.coroutines.launch

/**
 * connectedDevice foreground service that keeps the BLE link alive while a
 * background automation is enabled. It shows a low-priority persistent
 * notification; connectedDevice has no OS runtime cap (unlike dataSync).
 *
 * It is only started when [AppSettings.serviceEnabled] is set, so nothing runs
 * in the background by default.
 */
class DjiPowerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var bridge: MqttBridge? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundNow(notification(title(Conn.state.value), Conn.status.value))
        Conn.start(this)
        scope.launch {
            // Re-post only when the visible text changes, not on every telemetry report.
            combine(Conn.state, Conn.status) { s, st -> title(s) to st }
                .distinctUntilChanged()
                .collect { (t, st) -> nm().notify(NOTIF_ID, notification(t, st)) }
        }
        val engine = AutomationEngine(this)
        scope.launch {
            var lastStorm = 0L
            while (isActive) {
                runCatching { engine.evaluate() }
                val n = android.os.SystemClock.elapsedRealtime()
                if (n - lastStorm >= STORM_POLL_MS) {
                    val retry = runCatching { engine.pollStorm() }.getOrDefault(false)
                    // A storm write that didn't land is retried in ~2 min, not 20.
                    lastStorm = if (retry) n - STORM_POLL_MS + STORM_RETRY_MS else n
                }
                delay(20_000)
            }
        }
        bridge = MqttBridge(this)
        scope.launch(Dispatchers.IO) { runCatching { bridge?.start() } }
        scope.launch {
            // Switch changes arrive just after the report that carries them, so
            // publish on either so the AC switch state in HA isn't one report stale.
            combine(Conn.state, Conn.switches) { st, sw -> st to sw.firstOrNull { it.type == 2 }?.on }
                .collect { (st, acOn) -> st?.let { bridge?.publishState(stateJson(it, acOn)) } }
        }
    }

    private fun stateJson(s: com.djipower.companion.protocol.PowerState, acOn: Boolean?): String =
        JSONObject().apply {
            put("ac_on", acOn ?: JSONObject.NULL)
            put("battery", s.batteryPercent ?: JSONObject.NULL)
            put("out_w", s.outputW ?: 0)
            put("in_w", s.inputW ?: 0)
            put("ac_w", s.acOutputW)
            put("dc_w", s.dcOutputW)
            put("runtime_min", s.runtimeMin ?: JSONObject.NULL)
            put("charging", s.charging ?: JSONObject.NULL)
        }.toString()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Conn.start(this)
        // Settings screen re-syncs the service after edits; rebuild the MQTT client
        // only if its settings actually changed.
        val b = bridge
        if (b != null && b.configKey != null && b.configKey != MqttBridge.keyOf(AppSettings(this))) {
            scope.launch(Dispatchers.IO) { runCatching { b.start() } }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { bridge?.shutdown() }
        scope.cancel()
        // Without the service nothing keeps the process's BLE link justified; drop it
        // unless the UI is on screen and still using it.
        if (!Conn.uiActive) Conn.stop()
        super.onDestroy()
    }

    private fun startForegroundNow(n: Notification) {
        ServiceCompat.startForeground(
            this, NOTIF_ID, n,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )
    }

    private fun nm(): NotificationManager = getSystemService(NotificationManager::class.java)

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm().createNotificationChannel(
                NotificationChannel(CHANNEL, "Station monitor", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Keeps the DJI Power connection alive for automations." },
            )
        }
    }

    private fun title(s: PowerState?): String =
        if (s != null) {
            val pct = s.batteryPercent?.let { "${it.toInt()}%" } ?: "—"
            val out = s.outputW?.takeIf { it > 0 }?.let { " • ${it}W out" } ?: ""
            val inp = s.inputW?.takeIf { it > 0 }?.let { " • ${it}W in" } ?: ""
            "DJI Power $pct$out$inp"
        } else {
            "DJI Power"
        }

    private fun notification(title: String, status: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL = "station_monitor"
        private const val NOTIF_ID = 1001
        private const val STORM_POLL_MS = 20 * 60_000L
        private const val STORM_RETRY_MS = 2 * 60_000L

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, DjiPowerService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, DjiPowerService::class.java))
        }

        /** Start or stop the service to match the saved gate. Call after toggling a feature. */
        fun sync(ctx: Context) {
            if (AppSettings(ctx).serviceEnabled) start(ctx) else stop(ctx)
        }
    }
}
