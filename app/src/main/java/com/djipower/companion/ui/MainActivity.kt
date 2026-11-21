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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.djipower.companion.ble.DeviceScanner
import com.djipower.companion.ble.DjiPowerDevice
import com.djipower.companion.data.CredStore
import androidx.compose.ui.Alignment
import com.djipower.companion.protocol.EnergyConfig
import com.djipower.companion.protocol.PortSwitch
import com.djipower.companion.protocol.PowerState
import kotlinx.coroutines.launch

/**
 * Routes first-run onboarding -> dashboard. Credentials live encrypted on-device
 * (CredStore); the DJI account is used only during onboarding.
 */
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
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    var onboarded by remember { mutableStateOf(creds.isOnboarded) }
                    if (!onboarded) {
                        OnboardingScreen(creds, scanner, onDone = { onboarded = true })
                    } else {
                        Dashboard(creds, onSignOut = { creds.clear(); device?.disconnect(); onboarded = false })
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

        Column(
            Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(creds.stationName ?: "DJI Power 1000",
                    style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = { confirmForget = true }) { Text("Forget station") }
            }
            Text("Status: $status")

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Status") })
                FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Energy") })
            }
            HorizontalDivider()

            if (tab == 0) {
                Text("Live readings from your station, updated over Bluetooth.",
                    style = MaterialTheme.typography.bodySmall)
                state?.let { s ->
                    s.batteryPercent?.let { Text("Battery: ${"%.1f".format(it)} %",
                        style = MaterialTheme.typography.titleLarge) }
                    s.runtimeMin?.let { Text("Remaining: ${it / 60} h ${it % 60} min") }
                    Text("Output: ${s.outputW ?: 0} W     Input: ${s.inputW ?: 0} W")
                    Text("AC out: ${s.acOutputW} W     DC out: ${s.dcOutputW} W")
                    s.temperatureC?.let { Text("Battery temp: ${"%.1f".format(it)} °C") }
                    s.charging?.let { Text(if (it) "Charging" else "Discharging") }
                    HorizontalDivider()
                    Text("Outputs", style = MaterialTheme.typography.titleMedium)
                    Text("Turn a station output on or off. Each toggle shows the output's actual state.",
                        style = MaterialTheme.typography.bodySmall)
                    if (switches.isEmpty()) {
                        Text("No controllable outputs reported yet.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    switches.forEach { sw ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(sw.label)
                            Switch(checked = sw.on, onCheckedChange = { v ->
                                scope.launch {
                                    runCatching { device?.setPortSwitch(sw.type, sw.seq, v) }
                                        .onFailure { status = "switch failed: ${it.message}" }
                                }
                            })
                        }
                    }
                } ?: Text("Waiting for first telemetry…")
            } else {
                EnergyPanel(energy, scope) { status = it }
            }
        }

        if (confirmForget) {
            AlertDialog(
                onDismissRequest = { confirmForget = false },
                title = { Text("Forget this station?") },
                text = {
                    Text("This removes the saved pairing key from this phone and disconnects. " +
                        "To reconnect later you'll sign in to DJI once more to fetch the key again.")
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
    private fun EnergyPanel(
        energy: EnergyConfig?,
        scope: kotlinx.coroutines.CoroutineScope,
        onError: (String) -> Unit,
    ) {
        val cfg = energy ?: run { Text("Loading energy settings…"); return }
        val cl = cfg.limits
        var dsch by remember(cfg) { mutableStateOf(cl.discharge.toFloat()) }
        var rch by remember(cfg) { mutableStateOf(cl.recharge.toFloat()) }

        Text("Charge & discharge limits", style = MaterialTheme.typography.titleMedium)
        Text("The station stops discharging below the discharge limit and stops charging at " +
            "the recharge limit. A narrower window (e.g. 20–80%) noticeably extends battery life.",
            style = MaterialTheme.typography.bodySmall)
        Text("Discharge: ${dsch.toInt()}%      Recharge: ${rch.toInt()}%")
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

        cfg.reserve?.takeIf { it.available }?.let { r ->
            var on by remember(cfg) { mutableStateOf(r.enabled) }
            var pct by remember(cfg) { mutableStateOf(r.percent.toFloat()) }
            HorizontalDivider()
            Text("When several charging sources are connected, only solar charges above this " +
                "level; below it, all sources charge. Useful for solar + grid/car setups.",
                style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("Custom backup reserve", style = MaterialTheme.typography.titleMedium)
                Switch(checked = on, onCheckedChange = { v ->
                    on = v
                    scope.launch {
                        runCatching { device?.setReserve(enabled = v) }
                            .onFailure { onError("reserve toggle failed: ${it.message}") }
                    }
                })
            }
            if (on) {
                val lo = (cl.discharge + 5).coerceAtMost(cl.recharge)
                val hi = cl.recharge
                Text("Reserve level: ${pct.toInt()}%")
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

    override fun onDestroy() {
        super.onDestroy()
        device?.disconnect()
    }
}
