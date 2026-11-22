package com.djipower.companion.service

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.djipower.companion.data.AppSettings
import com.djipower.companion.data.Conn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Evaluates the background automations against live telemetry on a periodic
 * tick (~20s). All AC-controlling rules fold into a single desired AC state
 * with a clear precedence, so they can't fight each other:
 *
 *   base mode:  solar priority  >  duty cycle
 *   force OFF:  zero-load cutoff
 *   force OFF:  reserve (SOC) cutoff  — always wins
 *
 * [pollStorm] is a slower, network-backed check (NWS alerts) that raises the
 * charge limit to full during a storm and restores it afterward.
 */
class AutomationEngine(context: Context) {
    private val app = context.applicationContext
    private var zeroBelowSince = 0L
    private var dutyAnchor = 0L
    private var dutyOn = true
    private var lastLow = false
    private var lastGrid = false

    private fun now() = SystemClock.elapsedRealtime()

    suspend fun evaluate() {
        val s = AppSettings(app)
        val state = Conn.state.value ?: return
        val switches = Conn.switches.value
        val device = Conn.device ?: return
        val acOn = switches.firstOrNull { it.type == 2 }?.on
        val battery = state.batteryPercent
        val inputW = state.inputW ?: 0
        val n = now()

        var desired: Boolean? = null

        // --- Base mode: what should AC be doing on its own? ---
        if (s.solarPriorityEnabled) {
            desired = inputW >= s.solarThresholdW
        } else if (s.dutyCycleEnabled) {
            desired = dutyState(s, n)
        } else {
            dutyAnchor = 0L
        }

        // --- Zero-load cutoff (only when no base mode owns AC) ---
        if (s.zeroLoadEnabled && !s.solarPriorityEnabled && !s.dutyCycleEnabled) {
            if (acOn == true && state.acOutputW < s.zeroLoadThresholdW) {
                if (zeroBelowSince == 0L) zeroBelowSince = n
                else if (n - zeroBelowSince >= s.zeroLoadMinutes.coerceAtLeast(1) * 60_000L) {
                    desired = false
                }
            } else {
                zeroBelowSince = 0L
            }
        } else {
            zeroBelowSince = 0L
        }

        // --- Reserve (SOC) cutoff always wins ---
        if (s.socCutoffEnabled && battery != null && battery <= s.socCutoffPercent) {
            desired = false
        }

        // --- Broadcast events for Tasker / MacroDroid ---
        val low = battery != null && battery <= EVENT_LOW_BATTERY
        if (low && !lastLow) broadcastEvent("LOW_BATTERY")
        lastLow = low
        val gridNow = inputW >= GRID_PRESENT_W
        if (!gridNow && lastGrid) broadcastEvent("GRID_LOST")
        lastGrid = gridNow

        // --- Apply only on a real change ---
        if (desired != null && acOn != null && desired != acOn) {
            runCatching { device.setAcOutput(desired) }
        }
    }

    /** Storm prep: raise charge limit to full while a storm alert is active for the
     *  saved location, and restore the prior limit when it clears. Call on a slow
     *  cadence (NWS alerts change on the scale of minutes/hours).
     *
     *  The stored storm state only changes after the station accepts the write, so
     *  a failed write (station out of range, not yet connected) is retried rather
     *  than leaving the limit stuck. Returns true when a write is still owed and the
     *  caller should poll again soon. */
    suspend fun pollStorm(): Boolean {
        val s = AppSettings(app)
        if (!s.stormPrepEnabled) return false
        val lat = s.stormLat.toDouble()
        val lon = s.stormLon.toDouble()
        if (lat == 0.0 && lon == 0.0) return false
        val active = withContext(Dispatchers.IO) { WeatherMonitor.stormActive(lat, lon) } ?: return false
        if (active == s.stormActive) return false
        val limits = Conn.energy.value?.limits ?: return true
        val device = Conn.device ?: return true
        if (active) {
            val saved = limits.recharge
            val ok = runCatching { device.setChargeLimits(limits.discharge, limits.rechargeMax) }.isSuccess
            if (!ok) return true
            s.stormSavedCharge = saved
            s.stormActive = true
        } else {
            val restore = s.stormSavedCharge.takeIf { it in 1..100 } ?: limits.recharge
            val ok = runCatching { device.setChargeLimits(limits.discharge, restore) }.isSuccess
            if (!ok) return true
            s.stormActive = false
        }
        return false
    }

    private fun broadcastEvent(event: String) {
        runCatching {
            app.sendBroadcast(
                Intent(EVENT_ACTION).putExtra("event", event).setPackage(null),
            )
        }
    }

    private companion object {
        const val EVENT_ACTION = "com.djipower.companion.EVENT"
        const val EVENT_LOW_BATTERY = 15.0
        const val GRID_PRESENT_W = 10
    }

    private fun dutyState(s: AppSettings, n: Long): Boolean {
        val onMs = s.dutyOnMin.coerceAtLeast(1) * 60_000L
        val periodMs = s.dutyPeriodMin.coerceAtLeast(s.dutyOnMin + 1) * 60_000L
        val offMs = (periodMs - onMs).coerceAtLeast(60_000L)
        if (dutyAnchor == 0L) {
            dutyAnchor = n; dutyOn = true; return true
        }
        val elapsed = n - dutyAnchor
        if (dutyOn && elapsed >= onMs) {
            dutyOn = false; dutyAnchor = n
        } else if (!dutyOn && elapsed >= offMs) {
            dutyOn = true; dutyAnchor = n
        }
        return dutyOn
    }
}
