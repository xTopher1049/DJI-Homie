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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundNow(notification(Conn.state.value, Conn.status.value))
        Conn.start(this)
        scope.launch {
            combine(Conn.state, Conn.status) { s, st -> s to st }.collect { (s, st) ->
                nm().notify(NOTIF_ID, notification(s, st))
            }
        }
        val engine = AutomationEngine(this)
        scope.launch {
            while (isActive) {
                runCatching { engine.evaluate() }
                delay(20_000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Conn.start(this)
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
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

    private fun notification(s: PowerState?, status: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (s != null) {
            val pct = s.batteryPercent?.let { "${it.toInt()}%" } ?: "—"
            val out = s.outputW?.takeIf { it > 0 }?.let { " • ${it}W out" } ?: ""
            val inp = s.inputW?.takeIf { it > 0 }?.let { " • ${it}W in" } ?: ""
            "DJI Power $pct$out$inp"
        } else {
            "DJI Power"
        }
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
