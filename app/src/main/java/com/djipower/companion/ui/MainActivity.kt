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
import com.djipower.companion.protocol.EnergyConfig
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
            ).also { it.connect() }
        }

        Column(
            Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(creds.stationName ?: "DJI Power 1000",
                    style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = onSignOut) { Text("Sign out") }
            }
            Text("Status: $status")

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Status") })
                FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Energy") })
            }
            HorizontalDivider()

            if (tab == 0) {
                state?.let { s ->
                    s.batteryPercent?.let { Text("Battery: ${"%.1f".format(it)} %",
                        style = MaterialTheme.typography.titleLarge) }
                    s.runtimeMin?.let { Text("Remaining: ${it / 60} h ${it % 60} min") }
                    Text("Output: ${s.outputW ?: 0} W     Input: ${s.inputW ?: 0} W")
                    Text("AC out: ${s.acOutputW} W     DC out: ${s.dcOutputW} W")
                    s.temperatureC?.let { Text("Battery temp: ${"%.1f".format(it)} °C") }
                    s.charging?.let { Text(if (it) "Charging" else "Discharging") }
                    HorizontalDivider()
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { scope.launch {
                            runCatching { device?.setAcOutput(true) }
                                .onFailure { status = "AC on failed: ${it.message}" } } }) { Text("AC On") }
                        OutlinedButton(onClick = { scope.launch {
                            runCatching { device?.setAcOutput(false) }
                                .onFailure { status = "AC off failed: ${it.message}" } } }) { Text("AC Off") }
                    }
                } ?: Text("Waiting for first telemetry…")
            } else {
                EnergyPanel(energy, scope) { status = it }
            }
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

        Text("Discharge limit: ${dsch.toInt()}%      Recharge limit: ${rch.toInt()}%",
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

        cfg.reserve?.takeIf { it.available }?.let { r ->
            var on by remember(cfg) { mutableStateOf(r.enabled) }
            var pct by remember(cfg) { mutableStateOf(r.percent.toFloat()) }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
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
