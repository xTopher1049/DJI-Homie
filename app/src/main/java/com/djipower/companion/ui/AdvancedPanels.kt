package com.djipower.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.djipower.companion.data.Conn
import com.djipower.companion.protocol.Advanced
import com.djipower.companion.protocol.EnergyConfig
import com.djipower.companion.protocol.TimePeriod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale

private fun CoroutineScope.act(onError: (String) -> Unit, block: suspend () -> Unit) {
    launch { runCatching { block() }.onFailure { onError(it.message ?: "Command failed") } }
}

/** Electricity price periods + Scheduled Periods charge/discharge controls. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SchedulePanel(cfg: EnergyConfig, scope: CoroutineScope, onError: (String) -> Unit) {
    val sched = cfg.schedule ?: return
    val periods = sched.periods ?: return
    if (!cfg.energySaverAvailable) return
    val rules = cfg.stationRules
    if (rules != null && Advanced.ENERGY_SAVER_RULE !in rules) return
    val scheduled = cfg.energySaverMode == "Scheduled"
    var adding by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Electricity price periods", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Text("Peak and off-peak windows used by Scheduled Periods and Time-of-use.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (periods.isEmpty()) Text("No periods set.", style = MaterialTheme.typography.bodySmall)
            periods.forEach { p ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text((if (p.peak) "Peak" else "Off-peak") +
                            "  ${Advanced.fmtTime(p.start)}–${Advanced.fmtTime(p.end)}")
                        Text(daysLabel(p.days), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { scope.act(onError) { Conn.device?.setTimePeriods(periods - p) } }) {
                        Text("Remove")
                    }
                }
            }
            if (adding) {
                PeriodEditor(onCancel = { adding = false }) { np ->
                    val next = periods + np
                    val problem = Advanced.validatePeriods(next)
                    if (problem != null) {
                        onError(problem)
                    } else {
                        adding = false
                        scope.act(onError) { Conn.device?.setTimePeriods(next) }
                    }
                }
            } else {
                OutlinedButton(onClick = { adding = true }) { Text("Add period") }
            }

            HorizontalDivider()
            if (scheduled) {
                ToggleRow("Discharge during peak periods", sched.peakDischarge == true) { on ->
                    if (on && periods.none { it.peak }) onError("Add a peak period first")
                    else scope.act(onError) { Conn.device?.setScheduledSwitch(true, on) }
                }
                ToggleRow("Charge during off-peak periods", sched.offPeakCharge == true) { on ->
                    if (on && periods.none { !it.peak }) onError("Add an off-peak period first")
                    else scope.act(onError) { Conn.device?.setScheduledSwitch(false, on) }
                }
                sched.offPeakPower?.let { f ->
                    ApplyNumber("Off-peak charging power", f.value, f.min, f.max, "W") { w ->
                        scope.act(onError) { Conn.device?.setOffPeakChargePower(w) }
                    }
                }
            } else {
                Text("Choose Scheduled in Energy Saver to discharge at peak and charge off-peak.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeriodEditor(onCancel: () -> Unit, onSave: (TimePeriod) -> Unit) {
    var peak by remember { mutableStateOf(true) }
    var days by remember { mutableStateOf((0..6).toSet()) }
    var start by remember { mutableStateOf("17:00") }
    var end by remember { mutableStateOf("21:00") }
    var err by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(peak, { peak = true }, { Text("Peak") })
            FilterChip(!peak, { peak = false }, { Text("Off-peak") })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Advanced.DAY_NAMES.forEachIndexed { i, d ->
                FilterChip(i in days, { days = if (i in days) days - i else days + i }, { Text(d) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(start, { start = it }, label = { Text("Start") }, singleLine = true,
                modifier = Modifier.weight(1f))
            OutlinedTextField(end, { end = it }, label = { Text("End") }, singleLine = true,
                modifier = Modifier.weight(1f))
        }
        Text("24-hour HH:MM. An end earlier than the start runs past midnight.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(onClick = {
                val s = Advanced.parseTime(start)
                val e = Advanced.parseTime(end)
                err = when {
                    s == null || e == null -> "Use HH:MM times"
                    s == e -> "Start and end must differ"
                    days.isEmpty() -> "Pick at least one day"
                    else -> null
                }
                if (err == null && s != null && e != null) onSave(TimePeriod(peak, days, s, e))
            }) { Text("Save") }
        }
    }
}

/** SDC car-charger accessories (1 kW / 1.8 kW): on/off, mode, and per-mode limits. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CarChargerPanel(cfg: EnergyConfig, scope: CoroutineScope, onError: (String) -> Unit) {
    cfg.carChargers.filter { it.controllable }.forEach { c ->
        key(c.interfaceType, c.seq) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(c.name + if (c.seq > 1) " ${c.seq}" else "",
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f))
                        Switch(checked = c.enabled, onCheckedChange = { on ->
                            scope.act(onError) { Conn.device?.setCarChargerEnabled(c, on) }
                        })
                    }
                    if (!c.enabled) {
                        Text("Off — turn it on to set its mode and limits.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Advanced.CAR_MODE_NAMES.forEach { (m, name) ->
                                FilterChip(c.mode == m,
                                    { scope.act(onError) { Conn.device?.setCarChargerMode(c, m) } },
                                    { Text(name) })
                            }
                        }
                        Text(modeHint(c.mode), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Advanced.editableFields(c.mode, cfg.carAutoThreshold).forEach { field ->
                            val f = c.fields[field]
                            val info = Advanced.CAR_FIELD_INFO[field]
                            if (f != null && info != null && f.max > 0) {
                                key(field) {
                                    ApplyNumber(info.first, f.value, f.min, f.max, info.second, info.third) { raw ->
                                        scope.act(onError) { Conn.device?.setCarChargerField(c, field, raw) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun modeHint(mode: Int) = when (mode) {
    1 -> "Auto: switches direction by the vehicle battery's voltage."
    2 -> "Recharge: charges the station from the vehicle, stopping at the minimum voltage."
    3 -> "Charge: charges the vehicle's battery from the station up to the charge voltage."
    else -> ""
}

private fun daysLabel(days: Set<Int>) = when {
    days.size == 7 -> "Every day"
    days == setOf(0, 1, 2, 3, 4) -> "Weekdays"
    days == setOf(5, 6) -> "Weekends"
    else -> days.sorted().joinToString(" ") { Advanced.DAY_NAMES[it] }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Numeric setting with explicit Set button (no write per keystroke). [scale] = raw per unit. */
@Composable
private fun ApplyNumber(
    label: String,
    raw: Int,
    minRaw: Int,
    maxRaw: Int,
    unit: String,
    scale: Int = 1,
    onApply: (Int) -> Unit,
) {
    fun show(r: Int) = if (scale == 1) r.toString() else String.format(Locale.US, "%.2f", r / scale.toDouble())
    var text by remember(raw) { mutableStateOf(show(raw)) }
    val parsed = text.toDoubleOrNull()?.let { Math.round(it * scale).toInt() }
    val valid = parsed != null && parsed in minRaw..maxRaw
    Column {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { v -> text = v.filter { it.isDigit() || it == '.' } },
                label = { Text(label) },
                suffix = { Text(unit) },
                singleLine = true,
                isError = !valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            Button(enabled = valid && parsed != raw, onClick = { parsed?.let(onApply) }) { Text("Set") }
        }
        Text("Range ${show(minRaw)}–${show(maxRaw)} $unit",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
