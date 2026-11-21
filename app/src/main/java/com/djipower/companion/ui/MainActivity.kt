package com.djipower.companion.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.djipower.companion.ble.DeviceScanner
import com.djipower.companion.ble.DjiPowerDevice
import com.djipower.companion.data.CredStore
import com.djipower.companion.protocol.EnergyConfig
import com.djipower.companion.protocol.PortSwitch
import com.djipower.companion.protocol.PowerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Routes first-run onboarding -> dashboard. */
class MainActivity : ComponentActivity() {

    private var device: DjiPowerDevice? = null

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestBlePermissions()
        val creds = CredStore(this)
        val scanner = DeviceScanner(this)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) DjiDarkScheme else DjiLightScheme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var onboarded by remember { mutableStateOf(creds.isOnboarded) }
                    if (!onboarded) {
                        OnboardingScreen(creds, scanner, onDone = { onboarded = true })
                    } else {
                        Dashboard(creds, onSignOut = {
                            creds.clear(); device?.disconnect(); onboarded = false
                        })
                    }
                }
            }
        }
    }

    private fun requestBlePermissions() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        permLauncher.launch(perms)
    }

    @Composable
    private fun Dashboard(creds: CredStore, onSignOut: () -> Unit) {
        val scope = rememberCoroutineScope()
        var status by remember { mutableStateOf("Idle") }
        var state by remember { mutableStateOf<PowerState?>(null) }
        var energy by remember { mutableStateOf<EnergyConfig?>(null) }
        var tab by remember { mutableStateOf(0) }
        var switches by remember { mutableStateOf<List<PortSwitch>>(emptyList()) }
        var confirmForget by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            val mac = creds.mac ?: return@LaunchedEffect
            val key = creds.pairKey ?: return@LaunchedEffect
            status = "Connecting…"
            device?.disconnect()
            device = DjiPowerDevice(
                context = this@MainActivity, address = mac, pairKeyHex = key,
                onState = { s -> runOnUiThread { state = s } },
                onEvent = { e -> runOnUiThread { status = e } },
                onConfig = { c -> runOnUiThread { energy = c } },
                onSwitches = { sw -> runOnUiThread { switches = sw } },
            ).also { it.connect() }
        }
        LaunchedEffect(tab) { if (tab == 1) runCatching { device?.readConfig() } }

        Column(
            Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(creds.stationName ?: "DJI Power 1000",
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(status, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { confirmForget = true }) { Text("Forget") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(tab == 0, { tab = 0 }, { Text("Status") })
                FilterChip(tab == 1, { tab = 1 }, { Text("Energy") })
            }

            if (tab == 0) StatusTab(state, switches, scope) { status = it }
            else EnergyPanel(energy, scope) { status = it }
        }

        if (confirmForget) {
            AlertDialog(
                onDismissRequest = { confirmForget = false },
                title = { Text("Forget this station?") },
                text = {
                    Text("Removes the saved pairing key from this phone and disconnects. " +
                        "To reconnect you'll sign in to DJI once more to fetch it again.")
                },
                confirmButton = {
                    TextButton(onClick = { confirmForget = false; onSignOut() }) { Text("Forget") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmForget = false }) { Text("Cancel") }
                },
            )
        }
    }

    @Composable
    private fun StatusTab(
        state: PowerState?, switches: List<PortSwitch>,
        scope: CoroutineScope, onError: (String) -> Unit,
    ) {
        val s = state ?: run {
            Card(Modifier.fillMaxWidth()) {
                Text("Waiting for first telemetry…", Modifier.padding(20.dp))
            }
            return
        }

        BatteryCard(s)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(Modifier.weight(1f), "Output", "${s.outputW ?: 0} W",
                Icons.Filled.ArrowUpward, MaterialTheme.colorScheme.secondary)
            StatCard(Modifier.weight(1f), "Input", "${s.inputW ?: 0} W",
                Icons.Filled.ArrowDownward, MaterialTheme.colorScheme.tertiary)
        }

        val switchKeys = switches.map { it.type to it.seq }.toSet()
        val wattsByKey = s.ports.associateBy { it.type to it.seq }

        Text("Outputs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (switches.isEmpty() && s.ports.isEmpty()) {
            Text("No outputs reported yet.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        switches.forEach { sw ->
            val pp = wattsByKey[sw.type to sw.seq]
            PortCard(sw.label, portIcon(sw.type), portDetail(pp?.outputW ?: 0, pp?.inputW ?: 0, sw.on),
                checked = sw.on, onToggle = { v ->
                    scope.launch {
                        runCatching { device?.setPortSwitch(sw.type, sw.seq, v) }
                            .onFailure { onError("switch failed: ${it.message}") }
                    }
                })
        }
        // Telemetry-only ports (power but not switchable)
        s.ports.filter { (it.type to it.seq) !in switchKeys }.forEach { pp ->
            PortCard(pp.label, portIcon(pp.type), portDetail(pp.outputW, pp.inputW, pp.on),
                checked = null, onToggle = null)
        }
    }

    @Composable
    private fun EnergyPanel(energy: EnergyConfig?, scope: CoroutineScope, onError: (String) -> Unit) {
        val cfg = energy ?: run {
            Card(Modifier.fillMaxWidth()) { Text("Loading energy settings…", Modifier.padding(20.dp)) }
            return
        }
        val cl = cfg.limits
        var dsch by remember(cfg) { mutableStateOf(cl.discharge.toFloat()) }
        var rch by remember(cfg) { mutableStateOf(cl.recharge.toFloat()) }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Charge & discharge limits", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Text("The station stops discharging below the discharge limit and stops charging " +
                    "at the recharge limit. A narrower window (e.g. 20–80%) extends battery life.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Discharge ${dsch.toInt()}%   →   Recharge ${rch.toInt()}%",
                    style = MaterialTheme.typography.titleMedium)
                RangeSlider(
                    value = dsch..rch,
                    onValueChange = { r -> dsch = r.start; rch = r.endInclusive },
                    valueRange = cl.dischargeMin.toFloat()..cl.rechargeMax.toFloat(),
                    onValueChangeFinished = {
                        scope.launch {
                            runCatching { device?.setChargeLimits(dsch.toInt(), rch.toInt()) }
                                .onFailure { onError("limit set failed: ${it.message}") }
                        }
                    },
                )
            }
        }

        cfg.reserve?.takeIf { it.available }?.let { r ->
            var on by remember(cfg) { mutableStateOf(r.enabled) }
            var pct by remember(cfg) { mutableStateOf(r.percent.toFloat()) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Custom backup reserve", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        Switch(checked = on, onCheckedChange = { v ->
                            on = v
                            scope.launch {
                                runCatching { device?.setReserve(enabled = v) }
                                    .onFailure { onError("reserve toggle failed: ${it.message}") }
                            }
                        })
                    }
                    Text("When several charging sources are connected, only solar charges above " +
                        "this level; below it, all sources charge.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (on) {
                        val lo = (cl.discharge + 5).coerceAtMost(cl.recharge)
                        val hi = cl.recharge
                        Text("Reserve level ${pct.toInt()}%", style = MaterialTheme.typography.titleMedium)
                        Slider(
                            value = pct.coerceIn(lo.toFloat(), hi.toFloat()),
                            onValueChange = { pct = it },
                            valueRange = lo.toFloat()..hi.toFloat(),
                            onValueChangeFinished = {
                                scope.launch {
                                    runCatching { device?.setReserve(percent = pct.toInt(), min = lo, max = hi) }
                                        .onFailure { onError("reserve level failed: ${it.message}") }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        device?.disconnect()
    }
}

// ---- Reusable pieces --------------------------------------------------------

private fun portIcon(type: Int): ImageVector = when (type) {
    2 -> Icons.Filled.Power
    3, 4 -> Icons.Filled.Usb
    else -> Icons.Filled.Bolt
}

private fun portDetail(outputW: Int, inputW: Int, on: Boolean): String = buildString {
    if (outputW > 0) append("$outputW W out")
    if (inputW > 0) { if (isNotEmpty()) append("   ·   "); append("$inputW W in") }
    if (isEmpty()) append(if (on) "on · idle" else "off")
}

@Composable
private fun BatteryCard(s: PowerState) {
    val pct = (s.batteryPercent ?: 0.0).toFloat()
    val barColor = when {
        pct > 50 -> MaterialTheme.colorScheme.primary
        pct >= 20 -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.error
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.BatteryChargingFull, null, tint = barColor)
                    Text("${"%.0f".format(pct)}%", style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold)
                }
                s.charging?.let {
                    AssistChip(onClick = {}, label = { Text(if (it) "Charging" else "Discharging") })
                }
            }
            LinearProgressIndicator(
                progress = { (pct / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(10.dp),
                color = barColor,
            )
            val sub = buildString {
                s.runtimeMin?.let { append("≈ ${it / 60} h ${it % 60} min remaining") }
                s.temperatureC?.let {
                    if (isNotEmpty()) append("    ·    ")
                    append("${"%.1f".format(it)} °C")
                }
            }
            if (sub.isNotEmpty()) {
                Text(sub, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StatCard(modifier: Modifier, title: String, value: String, icon: ImageVector, tint: androidx.compose.ui.graphics.Color) {
    Card(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
                Text(title, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PortCard(label: String, icon: ImageVector, detail: String, checked: Boolean?, onToggle: ((Boolean) -> Unit)?) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Column {
                    Text(label, style = MaterialTheme.typography.titleMedium)
                    Text(detail, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (checked != null && onToggle != null) {
                Switch(checked = checked, onCheckedChange = onToggle)
            }
        }
    }
}
