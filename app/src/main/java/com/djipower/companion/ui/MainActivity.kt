package com.djipower.companion.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.djipower.companion.ble.DjiPowerDevice
import com.djipower.companion.protocol.PowerState
import kotlinx.coroutines.launch

/**
 * v0.1 UI: manual MAC + pair-key entry -> connect -> live telemetry + AC toggle.
 * The cloud captcha-onboarding flow (DjiCloud) will drive a first-run wizard;
 * for now paste the key the fetch script printed.
 */
class MainActivity : ComponentActivity() {

    private var device: DjiPowerDevice? = null

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* result handled implicitly; user can retry connect */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestBlePermissions()
        setContent { MaterialTheme { Screen() } }
    }

    private fun requestBlePermissions() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        permLauncher.launch(perms)
    }

    @Composable
    private fun Screen() {
        val scope = rememberCoroutineScope()
        var mac by remember { mutableStateOf("E8:06:90:20:C5:36") }
        var key by remember { mutableStateOf("") }
        var status by remember { mutableStateOf("Idle") }
        var state by remember { mutableStateOf<PowerState?>(null) }

        Column(
            Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("DJI Power Companion", style = MaterialTheme.typography.headlineSmall)
            Text("Unofficial · local BLE · not affiliated with DJI",
                style = MaterialTheme.typography.bodySmall)

            OutlinedTextField(mac, { mac = it }, label = { Text("Station MAC") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(key, { key = it.trim() }, label = { Text("32-hex pair key") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))

            Button(onClick = {
                status = "Connecting…"
                state = null
                device?.disconnect()
                device = DjiPowerDevice(
                    context = this@MainActivity,
                    address = mac.trim(),
                    pairKeyHex = key.trim(),
                    onState = { s -> runOnUiThread { state = s } },
                    onEvent = { e -> runOnUiThread { status = e } },
                ).also { it.connect() }
            }, enabled = key.length == 32, modifier = Modifier.fillMaxWidth()) {
                Text("Connect")
            }

            Text("Status: $status")

            state?.let { s ->
                HorizontalDivider()
                s.batteryPercent?.let { Text("Battery: ${"%.1f".format(it)} %",
                    style = MaterialTheme.typography.titleLarge) }
                s.runtimeMin?.let { Text("Runtime: $it min") }
                Text("Output: ${s.outputW ?: 0} W   Input: ${s.inputW ?: 0} W")
                Text("AC out: ${s.acOutputW} W   DC out: ${s.dcOutputW} W")
                s.temperatureC?.let { Text("Battery temp: ${"%.1f".format(it)} °C") }
                s.charging?.let { Text(if (it) "Charging" else "Discharging") }

                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { scope.launch {
                        runCatching { device?.setAcOutput(true) }
                            .onFailure { status = "AC on failed: ${it.message}" }
                    } }) { Text("AC On") }
                    OutlinedButton(onClick = { scope.launch {
                        runCatching { device?.setAcOutput(false) }
                            .onFailure { status = "AC off failed: ${it.message}" }
                    } }) { Text("AC Off") }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        device?.disconnect()
    }
}
