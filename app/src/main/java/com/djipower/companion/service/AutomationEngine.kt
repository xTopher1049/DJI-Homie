package com.djipower.companion.service

import android.content.Context
import android.os.SystemClock
import com.djipower.companion.data.AppSettings
import com.djipower.companion.data.Conn

/**
 * Evaluates the background automations against live telemetry on a periodic
 * tick (~20s). All AC-controlling rules fold into a single desired AC state
 * with a clear precedence, so they can't fight each other:
 *
 *   base mode:  solar priority  >  duty cycle
 *   force OFF:  zero-load cutoff, storm/outage reserve
 *   force OFF:  reserve (SOC) cutoff  — always wins
 *
 * AC is switched via setAcOutput (0x0D type 2), the known-good AC switch,
 * and only when the desired state actually differs from the current one.
 */
class AutomationEngine(context: Context) {
    private val app = context.applicationContext
    private var zeroBelowSince = 0L
    private var outageSince = 0L
    private var wasCharging = false
    private var dutyAnchor = 0L
    private var dutyOn = true

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

        // --- Storm / outage reserve: grid was present then dropped to 0 ---
        if (s.stormReserveEnabled) {
            if (inputW >= GRID_PRESENT_W) {
                wasCharging = true
                outageSince = 0L
            } else if (wasCharging && inputW <= 0) {
                if (outageSince == 0L) outageSince = n
                else if (n - outageSince >= s.stormMinutes.coerceAtLeast(1) * 60_000L) {
                    desired = false
                }
            }
        } else {
            wasCharging = false
            outageSince = 0L
        }

        // --- Reserve (SOC) cutoff always wins ---
        if (s.socCutoffEnabled && battery != null && battery <= s.socCutoffPercent) {
            desired = false
        }

        // --- Apply only on a real change ---
        if (desired != null && acOn != null && desired != acOn) {
            runCatching { device.setAcOutput(desired) }
        }
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

    private companion object {
        const val GRID_PRESENT_W = 10 // input above this counts as "on grid / charging"
    }
}
