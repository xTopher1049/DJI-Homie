package com.djipower.companion.service

import android.content.Context
import android.os.SystemClock
import com.djipower.companion.data.AppSettings
import com.djipower.companion.data.Conn
import com.djipower.companion.protocol.PortSwitch
import com.djipower.companion.protocol.PowerState

/**
 * Evaluates the Phase 2 automations against live telemetry. The service calls
 * [evaluate] on a periodic tick (rules work on a minute scale, so ~20s is ample).
 *
 * All actions use the known-good switch commands:
 *  - AC on/off via setAcOutput (0x0D type 2)
 *  - master power off via setPortSwitch type 1 ("Power")
 */
class AutomationEngine(context: Context) {
    private val app = context.applicationContext
    private var zeroBelowSince = 0L
    private var idleSince = 0L
    private var dutyAnchor = 0L
    private var dutyOn = true

    /** elapsedRealtime is monotonic and unaffected by wall-clock changes. */
    private fun now() = SystemClock.elapsedRealtime()

    suspend fun evaluate() {
        val s = AppSettings(app)
        val state: PowerState = Conn.state.value ?: return
        val switches = Conn.switches.value
        val acOn = switches.firstOrNull { it.type == 2 }?.on
        val device = Conn.device ?: return

        // --- AC duty cycle (owns the AC output while enabled) ---
        if (s.dutyCycleEnabled) {
            val onMs = s.dutyOnMin.coerceAtLeast(1) * 60_000L
            val periodMs = s.dutyPeriodMin.coerceAtLeast(s.dutyOnMin + 1) * 60_000L
            val offMs = (periodMs - onMs).coerceAtLeast(60_000L)
            // don't duty AC back on below the reserve floor, if that rule is armed
            val belowReserve = s.socCutoffEnabled &&
                (state.batteryPercent?.let { it <= s.socCutoffPercent } ?: false)
            if (dutyAnchor == 0L) {
                dutyAnchor = now(); dutyOn = true
                if (!belowReserve) runCatching { device.setAcOutput(true) }
            } else {
                val elapsed = now() - dutyAnchor
                if (dutyOn && elapsed >= onMs) {
                    dutyOn = false; dutyAnchor = now(); runCatching { device.setAcOutput(false) }
                } else if (!dutyOn && elapsed >= offMs) {
                    dutyOn = true; dutyAnchor = now()
                    if (!belowReserve) runCatching { device.setAcOutput(true) }
                }
            }
        } else {
            dutyAnchor = 0L
        }

        // --- Zero-load AC cutoff (skip when duty cycle owns AC) ---
        if (s.zeroLoadEnabled && !s.dutyCycleEnabled && acOn == true) {
            if (state.acOutputW < s.zeroLoadThresholdW) {
                if (zeroBelowSince == 0L) zeroBelowSince = now()
                else if (now() - zeroBelowSince >= s.zeroLoadMinutes.coerceAtLeast(1) * 60_000L) {
                    runCatching { device.setAcOutput(false) }; zeroBelowSince = 0L
                }
            } else {
                zeroBelowSince = 0L
            }
        } else {
            zeroBelowSince = 0L
        }

        // --- SOC discharge cutoff (reserve DC/USB) ---
        if (s.socCutoffEnabled && acOn == true) {
            val pct = state.batteryPercent
            if (pct != null && pct <= s.socCutoffPercent) runCatching { device.setAcOutput(false) }
        }

        // --- Idle power-down: master power off when input AND output are 0 ---
        if (s.idlePowerDownEnabled) {
            val idle = (state.outputW ?: 0) == 0 && (state.inputW ?: 0) == 0
            if (idle) {
                if (idleSince == 0L) idleSince = now()
                else if (now() - idleSince >= s.idleMinutes.coerceAtLeast(1) * 60_000L) {
                    val powerSeq = switches.firstOrNull { it.type == 1 }?.seq ?: 1
                    runCatching { device.setPortSwitch(1, powerSeq, false) }; idleSince = 0L
                }
            } else {
                idleSince = 0L
            }
        } else {
            idleSince = 0L
        }
    }
}
