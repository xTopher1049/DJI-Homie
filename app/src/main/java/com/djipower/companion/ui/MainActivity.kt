package com.djipower.companion.ui

import android.Manifest
import android.os.Build
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.djipower.companion.ble.DeviceScanner
import com.djipower.companion.data.AppSettings
import com.djipower.companion.data.Conn
import com.djipower.companion.data.CredStore
import com.djipower.companion.data.HistoryStore
import com.djipower.companion.service.DjiPowerService
import com.djipower.companion.service.MqttBridge
import com.djipower.companion.protocol.DeviceInfo
import com.djipower.companion.protocol.EnergyConfig
import com.djipower.companion.protocol.PortSwitch
import com.djipower.companion.protocol.PowerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Routes first-run onboarding -> dashboard. */
class MainActivity : ComponentActivity() {

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    private val locationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) captureStormLocation() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestBlePermissions()
        val creds = CredStore(this)
        val scanner = DeviceScanner(this)
        val history = Conn.history(this)
        setContent {
            var themeMode by remember { mutableStateOf(creds.themeMode) }
            val dark = when (themeMode) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            MaterialTheme(colorScheme = if (dark) DjiDarkScheme else DjiLightScheme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var onboarded by remember { mutableStateOf(creds.isOnboarded) }
                    if (!onboarded) {
                        OnboardingScreen(creds, scanner, onDone = { onboarded = true })
                    } else {
                        Dashboard(
                            creds,
                            history = history,
                            themeMode = themeMode,
                            onThemeChange = { creds.themeMode = it; themeMode = it },
                            onSignOut = {
                                creds.clear()
                                AppSettings(this@MainActivity).clearAll()
                                DjiPowerService.stop(this@MainActivity)
                                Conn.stop()
                                onboarded = false
                            },
                        )
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

    /** Start/stop the background service to match the automation gate, prompting
     *  for the notification permission and a battery-optimisation exemption the
     *  first time a background automation is switched on. */
    fun syncServiceAndBattery() {
        DjiPowerService.sync(this)
        if (!AppSettings(this).serviceEnabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
        val pm = getSystemService(PowerManager::class.java)
        if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName"),
                    ),
                )
            }
        }
    }

    /** When storm prep is enabled, capture a coarse location once (foreground) so the
     *  background weather poll can query NWS alerts for the user's area. */
    fun onStormEnabled() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            captureStormLocation()
        } else {
            locationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    private fun captureStormLocation() {
        val lm = getSystemService(LocationManager::class.java) ?: return
        val loc = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        ).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (loc != null) {
            val st = AppSettings(this)
            st.stormLat = loc.latitude.toFloat()
            st.stormLon = loc.longitude.toFloat()
        }
    }

    @Composable
    private fun Dashboard(
        creds: CredStore,
        history: HistoryStore,
        themeMode: String,
        onThemeChange: (String) -> Unit,
        onSignOut: () -> Unit,
    ) {
        val scope = rememberCoroutineScope()
        val status by Conn.status.collectAsState()
        val state by Conn.state.collectAsState()
        val energy by Conn.energy.collectAsState()
        val switches by Conn.switches.collectAsState()
        val rssi by Conn.rssi.collectAsState()
        val deviceInfo by Conn.deviceInfo.collectAsState()
        val alarms by Conn.alarms.collectAsState()
        val historyRev by Conn.historyRev.collectAsState()
        val samples = remember(historyRev) { history.recent() }
        var tab by remember { mutableStateOf(0) }
        var confirmForget by remember { mutableStateOf(false) }
        var showSettings by remember { mutableStateOf(false) }
        var showUpsConfig by remember { mutableStateOf(false) }
        var showAutomations by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) { Conn.start(this@MainActivity) }
        LaunchedEffect(tab) { if (tab == 1) runCatching { Conn.device?.readConfig() } }

        // System back gesture closes an open menu instead of exiting.
        BackHandler(enabled = showSettings || showAutomations) {
            if (showAutomations) showAutomations = false else showSettings = false
        }

        if (showAutomations) {
            AutomationsScreen(
                onBack = { showAutomations = false },
                onChanged = { syncServiceAndBattery() },
                onStormEnabled = { onStormEnabled() },
            )
        } else if (showSettings) {
            SettingsScreen(
                themeMode = themeMode,
                onThemeChange = onThemeChange,
                deviceInfo = deviceInfo,
                scope = scope,
                onError = { Conn.postStatus(it) },
                onOpenAutomations = { showSettings = false; showAutomations = true },
                onForget = { confirmForget = true },
                onBack = { showSettings = false },
            )
        } else {
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
                    if (remember(showAutomations) { AppSettings(this@MainActivity).mqttEnabled }) {
                        MqttIndicator(detailed = false)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SignalMeter(rssi)
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(tab == 0, { tab = 0 }, { Text("Status") })
                FilterChip(tab == 1, { tab = 1 }, { Text("Energy") })
                FilterChip(tab == 2, { tab = 2 }, { Text("History") })
            }

            when (tab) {
                0 -> StatusTab(state, switches, alarms, scope, onAcConfig = { showUpsConfig = true }) { Conn.postStatus(it) }
                1 -> EnergyPanel(energy, scope) { Conn.postStatus(it) }
                else -> HistoryTab(samples)
            }
        }
        }

        if (showUpsConfig) {
            val upsOn = energy?.acTimeoutSec?.let { it == 0 } ?: false
            AlertDialog(
                onDismissRequest = { showUpsConfig = false },
                title = { Text("AC output · UPS mode") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("Keep AC on (UPS mode)")
                            Switch(checked = upsOn, onCheckedChange = { v ->
                                scope.launch {
                                    runCatching { Conn.device?.setAcAlwaysOn(v) }
                                        .onFailure { Conn.postStatus("UPS set failed: ${it.message}") }
                                }
                            })
                        }
                        Text("Normally the AC output auto-shuts off after a while when the load is " +
                            "low. Turn this on to keep AC powered no matter what — useful for running " +
                            "the station as a UPS. (Sets the AC-output timeout to Never.) Note: leaving " +
                            "it on draws more idle power, since the inverter stays active.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = { TextButton(onClick = { showUpsConfig = false }) { Text("Done") } },
            )
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
        state: PowerState?, switches: List<PortSwitch>, alarms: List<Pair<Long, String>>,
        scope: CoroutineScope, onAcConfig: () -> Unit, onError: (String) -> Unit,
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
                        runCatching { Conn.device?.setPortSwitch(sw.type, sw.seq, v) }
                            .onFailure { onError("switch failed: ${it.message}") }
                    }
                }, onConfig = if (sw.type == 2) onAcConfig else null)
        }
        // Telemetry-only ports (power but not switchable)
        s.ports.filter { (it.type to it.seq) !in switchKeys }.forEach { pp ->
            PortCard(pp.label, portIcon(pp.type), portDetail(pp.outputW, pp.inputW, pp.on),
                checked = null, onToggle = null)
        }

        if (alarms.isNotEmpty()) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("HMS health alarms", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer)
                    Text("Raw health/fault frames from the station (codes not yet decoded).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer)
                    alarms.takeLast(8).reversed().forEach { (t, hex) ->
                        Text("${hhmmss(t)}  ${hex.take(56)}${if (hex.length > 56) "…" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
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
                            runCatching { Conn.device?.setChargeLimits(dsch.toInt(), rch.toInt()) }
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
                                runCatching { Conn.device?.setReserve(enabled = v) }
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
                                    runCatching { Conn.device?.setReserve(percent = pct.toInt(), min = lo, max = hi) }
                                        .onFailure { onError("reserve level failed: ${it.message}") }
                                }
                            },
                        )
                    }
                }
            }
        }

        if (cfg.energySaverAvailable) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Energy Saver", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    if (cfg.energySaverModes.isEmpty()) {
                        Text("Mode: ${cfg.energySaverMode ?: "—"}")
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            cfg.energySaverModes.forEach { m ->
                                FilterChip(
                                    cfg.energySaverMode == m,
                                    {
                                        scope.launch {
                                            runCatching { Conn.device?.setEnergySaverMode(m) }
                                                .onFailure { onError("Energy Saver: ${it.message}") }
                                        }
                                    },
                                    { Text(m) },
                                )
                            }
                        }
                    }
                    Text("Grid-tied modes (Self-consumption / Time-of-use) must be set up in " +
                        "DJI Home first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        SchedulePanel(cfg, scope, onError)
        CarChargerPanel(cfg, scope, onError)
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun SettingsScreen(
        themeMode: String,
        onThemeChange: (String) -> Unit,
        deviceInfo: DeviceInfo?,
        scope: CoroutineScope,
        onError: (String) -> Unit,
        onOpenAutomations: () -> Unit,
        onForget: () -> Unit,
        onBack: () -> Unit,
    ) {
        Column(
            Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text("Advanced settings", style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold)
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Appearance", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    Text("Choose how the app looks.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    listOf("auto" to "Automatic (system)", "light" to "Light", "dark" to "Dark")
                        .forEach { (key, label) ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = themeMode == key, onClick = { onThemeChange(key) })
                                Text(label)
                            }
                        }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    SettingRow("Automations", "Set up \u203A") { onOpenAutomations() }
                }
            }

            var picker by remember { mutableStateOf<String?>(null) }
            val screenOpts = listOf(0 to "Never", 60 to "1 min", 300 to "5 min",
                900 to "15 min", 1800 to "30 min")
            val deviceOpts = listOf(0 to "Never", 60 to "1 min", 3600 to "1 hour", 21600 to "6 hours",
                43200 to "12 hours", 86400 to "24 hours")

            deviceInfo?.let { di ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Station settings", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        Text("These change settings on the station itself.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SettingRow("Screen timeout",
                            di.screenTimeoutS?.let { fmtTimeout(it) } ?: "—") { picker = "screen" }
                        SettingRow("Device timeout",
                            di.deviceTimeoutS?.let { fmtTimeout(it) } ?: "—") { picker = "device" }
                        Row(Modifier.fillMaxWidth().padding(top = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("Temperature unit")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(di.tempUnitFahrenheit == false,
                                    { scope.launch { runCatching { Conn.device?.setTempUnitFahrenheit(false) }
                                        .onFailure { onError("Couldn't set unit: ${it.message}") } } },
                                    { Text("°C") })
                                FilterChip(di.tempUnitFahrenheit == true,
                                    { scope.launch { runCatching { Conn.device?.setTempUnitFahrenheit(true) }
                                        .onFailure { onError("Couldn't set unit: ${it.message}") } } },
                                    { Text("°F") })
                            }
                        }
                    }
                }
            }

            if (picker != null) {
                val isScreen = picker == "screen"
                val opts = if (isScreen) screenOpts else deviceOpts
                val current = if (isScreen) deviceInfo?.screenTimeoutS else deviceInfo?.deviceTimeoutS
                val unitLabel = "minutes"
                var custom by remember(picker) {
                    mutableStateOf(
                        when {
                            current == null || current == 0 -> ""
                            else -> (current / 60).toString()
                        }
                    )
                }
                fun applyTimeout(sec: Int) {
                    scope.launch {
                        runCatching {
                            if (isScreen) Conn.device?.setScreenTimeout(sec)
                            else Conn.device?.setDeviceTimeout(sec)
                        }.onFailure { onError("Couldn't set timeout: ${it.message}") }
                    }
                    picker = null
                }
                AlertDialog(
                    onDismissRequest = { picker = null },
                    title = { Text(if (isScreen) "Screen timeout" else "Device timeout") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                opts.forEach { (sec, lbl) ->
                                    FilterChip(current == sec, { applyTimeout(sec) }, { Text(lbl) })
                                }
                            }
                            OutlinedTextField(
                                value = custom,
                                onValueChange = { v -> custom = v.filter { it.isDigit() || it == '.' } },
                                label = { Text("Custom ($unitLabel)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                            Text("0 = never. The station may round to its own steps; " +
                                "the value re-reads after saving so you'll see what it kept.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!isScreen) {
                                Text("If the station has already been idle longer than this, it " +
                                    "powers off right away.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val d = custom.toDoubleOrNull()
                            if (d != null) {
                                val sec = (d * 60).roundToInt()
                                applyTimeout(sec.coerceAtLeast(0))
                            } else picker = null
                        }) { Text("Set") }
                    },
                    dismissButton = { TextButton(onClick = { picker = null }) { Text("Cancel") } },
                )
            }

            deviceInfo?.let { di ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Device", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        di.firmware?.let { InfoRow("Firmware", it) }
                        di.dongleFirmware?.let { InfoRow("Dongle firmware", it) }
                        di.cycleCount?.let { InfoRow("Battery cycles", "$it") }
                        di.maintenanceCharging?.let {
                            InfoRow("Maintenance charge", if (it) "Active" else "Normal")
                        }
                        di.cloudConnected?.let { InfoRow("Station cloud link", if (it) "Connected" else "Offline") }
                        di.accessories.forEach { a ->
                            Column(Modifier.fillMaxWidth()) {
                                Text(a.name, style = MaterialTheme.typography.bodyMedium)
                                a.firmware?.let {
                                    Text("Firmware $it",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Text("DJI Homie itself runs fully locally over Bluetooth. “Station cloud " +
                            "link” shows the station's own dongle connection to DJI's servers, not " +
                            "this app.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            deviceInfo?.expansionBatteries?.takeIf { it.isNotEmpty() }?.let { packs ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Expansion batteries", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        packs.forEach { b ->
                            InfoRow("Pack ${b.seq}",
                                "${b.percent?.toInt() ?: "?"}%  ·  ${b.cycleCount} cyc  ·  ${b.ratedWh} Wh")
                            b.firmware?.let { InfoRow("  firmware", it) }
                        }
                    }
                }
            }

            OutlinedButton(onClick = onForget, modifier = Modifier.fillMaxWidth()) {
                Text("Forget station")
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Conn.uiActive = true
    }

    override fun onStop() {
        Conn.uiActive = false
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!AppSettings(this).serviceEnabled) Conn.stop()
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
private fun PortCard(
    label: String, icon: ImageVector, detail: String,
    checked: Boolean?, onToggle: ((Boolean) -> Unit)?, onConfig: (() -> Unit)? = null,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 8.dp),
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
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (onConfig != null) {
                    IconButton(onClick = onConfig) {
                        Icon(Icons.Filled.Settings, contentDescription = "Configure",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (checked != null && onToggle != null) {
                    Switch(checked = checked, onCheckedChange = onToggle)
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

@Composable
private fun SettingRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        Text(value, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun AutomationsScreen(
    onBack: () -> Unit,
    onChanged: () -> Unit,
    onStormEnabled: () -> Unit,
) {
    val context = LocalContext.current
    val s = remember { AppSettings(context) }
    var rev by remember { mutableStateOf(0) }
    fun changed() { rev++; onChanged() }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Automations", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold)
        }
        Text("These run in the background with a persistent notification. Turning any on " +
            "keeps a low-power connection alive; turning them all off stops it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        key(rev) {
            AutomationCard(
                "Zero-load AC cutoff",
                "Switches the AC inverter off when it idles below a set load, to stop parasitic drain.",
                s.zeroLoadEnabled, { s.zeroLoadEnabled = it; changed() },
            ) {
                NumField("Load under", s.zeroLoadThresholdW, "W") { s.zeroLoadThresholdW = it; changed() }
                NumField("For at least", s.zeroLoadMinutes, "min") { s.zeroLoadMinutes = it; changed() }
            }
            AutomationCard(
                "Reserve cutoff",
                "Switches AC off at a battery floor so USB/DC stays available for essentials.",
                s.socCutoffEnabled, { s.socCutoffEnabled = it; changed() },
            ) {
                NumField("Battery floor", s.socCutoffPercent, "%") { s.socCutoffPercent = it; changed() }
            }
            AutomationCard(
                "AC duty cycle",
                "Runs AC on a repeating on/off schedule to stretch runtime for intermittent loads.",
                s.dutyCycleEnabled, { s.dutyCycleEnabled = it; changed() },
            ) {
                NumField("AC on for", s.dutyOnMin, "min") { s.dutyOnMin = it; changed() }
                NumField("Every", s.dutyPeriodMin, "min") { s.dutyPeriodMin = it; changed() }
            }
            AutomationCard(
                "Storm prep (auto)",
                "Watches NWS weather alerts for your area and tops the battery to full during a " +
                "hurricane or tropical-storm watch/warning, then restores your normal charge limit " +
                "when it clears.",
                s.stormPrepEnabled, { s.stormPrepEnabled = it; changed(); if (it) onStormEnabled() },
            ) {
                val hasLoc = s.stormLat != 0f || s.stormLon != 0f
                val status = when {
                    s.stormActive -> "Storm alert active — charging to full"
                    !hasLoc -> "Waiting for location — keep location on and reopen"
                    else -> "Monitoring your area"
                }
                Text(status, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
            }
            AutomationCard(
                "Solar / alternator priority",
                "Runs AC only while charge input exceeds a threshold, so AC follows the sun or the " +
                "engine instead of draining the battery.",
                s.solarPriorityEnabled, { s.solarPriorityEnabled = it; changed() },
            ) {
                NumField("Input above", s.solarThresholdW, "W") { s.solarThresholdW = it; changed() }
            }
            AutomationCard(
                "MQTT bridge",
                "Publishes telemetry to a local MQTT broker (Mosquitto / Home Assistant) and accepts " +
                "AC on/off commands, so a spare phone can bridge BLE to Wi-Fi.",
                s.mqttEnabled, { s.mqttEnabled = it; changed() },
            ) {
                StrField("Broker host / IP", s.mqttHost) { s.mqttHost = it }
                NumField("Port", s.mqttPort, "") { s.mqttPort = it }
                StrField("Base topic", s.mqttBaseTopic) { s.mqttBaseTopic = it }
                StrField("Username (optional)", s.mqttUser) { s.mqttUser = it }
                StrField("Password (optional)", s.mqttPass, password = true) { s.mqttPass = it }
                MqttIndicator(detailed = true)
                OutlinedButton(onClick = { changed() }) { Text("Apply") }
                Text("Adds a \"DJI Power\" device to Home Assistant via MQTT discovery. Publishes " +
                    "${s.mqttBaseTopic.ifBlank { "dji_power" }}/state and …/availability · listens " +
                    "…/ac/set (on/off).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TaskerCard(s)
        }
    }
}

/**
 * MQTT bridge health: a coloured dot plus "connected · sent 3s ago". Green when
 * connected and a publish went out in the last minute, amber while connecting
 * (or connected but idle, e.g. station out of range), red on an error.
 * [detailed] adds the full status text (error reason) for the settings card.
 */
@Composable
private fun MqttIndicator(detailed: Boolean) {
    val health by MqttBridge.health.collectAsState()
    val text by MqttBridge.status.collectAsState()
    val lastSent by MqttBridge.lastSent.collectAsState()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(5_000) }
    }
    val age = if (lastSent > 0) ((now - lastSent).coerceAtLeast(0) / 1000) else -1
    val fresh = age in 0..60
    val (color, label) = when (health) {
        MqttBridge.Health.CONNECTED ->
            if (fresh) Color(0xFF2E7D32) to "MQTT connected · sent ${fmtAge(age)} ago"
            else Color(0xFFF9A825) to (if (age < 0) "MQTT connected · nothing sent yet"
                else "MQTT connected · last sent ${fmtAge(age)} ago")
        MqttBridge.Health.CONNECTING -> Color(0xFFF9A825) to "MQTT connecting…"
        MqttBridge.Health.ERROR -> MaterialTheme.colorScheme.error to "MQTT not connected"
        MqttBridge.Health.OFF -> MaterialTheme.colorScheme.onSurfaceVariant to "MQTT off (service not running)"
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).background(color, RoundedCornerShape(50)))
            Text(label, style = MaterialTheme.typography.bodySmall, color = color)
        }
        if (detailed && health != MqttBridge.Health.CONNECTED) {
            Text(text, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun fmtAge(s: Long): String = when {
    s < 60 -> "${s}s"
    s < 3600 -> "${s / 60}m"
    else -> "${s / 3600}h"
}

/** Shows the shared token Tasker / MacroDroid must send with the SET_AC broadcast. */
@Composable
private fun TaskerCard(s: AppSettings) {
    val clipboard = LocalClipboardManager.current
    var token by remember { mutableStateOf(s.commandToken) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Tasker / MacroDroid", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Text("Send broadcast com.djipower.companion.SET_AC with boolean extra \"on\" and " +
                "string extra \"token\" set to the value below. Broadcasts without it are ignored.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(token, fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(token)) }) {
                    Text("Copy")
                }
                OutlinedButton(onClick = { token = s.regenerateCommandToken() }) {
                    Text("New token")
                }
            }
        }
    }
}

@Composable
private fun AutomationCard(
    title: String,
    desc: String,
    enabled: Boolean,
    onEnabled: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = onEnabled)
            }
            Text(desc, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (enabled) content()
        }
    }
}

@Composable
private fun NumField(label: String, value: Int, suffix: String, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v.filter { it.isDigit() }
            text.toIntOrNull()?.let(onChange)
        },
        label = { Text(label) },
        suffix = { Text(suffix) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun StrField(
    label: String,
    value: String,
    password: Boolean = false,
    onChange: (String) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value) }
    var reveal by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onChange(it) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (password && !reveal) PasswordVisualTransformation()
            else VisualTransformation.None,
        keyboardOptions = if (password) KeyboardOptions(keyboardType = KeyboardType.Password)
            else KeyboardOptions.Default,
        trailingIcon = if (password) {
            {
                IconButton(onClick = { reveal = !reveal }) {
                    Icon(
                        if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (reveal) "Hide password" else "Show password",
                    )
                }
            }
        } else null,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun hhmmss(t: Long): String =
    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(t))

private fun fmtTimeout(s: Int): String = when {
    s == 0 -> "Never"
    s % 3600 == 0 -> "${s / 3600} h"
    else -> "${s / 60} min"
}

@Composable
private fun HistoryTab(samples: List<HistoryStore.Sample>) {
    if (samples.size < 2) {
        Card(Modifier.fillMaxWidth()) {
            Text("Collecting data… charts appear after a couple of samples (recorded every ~20 s).",
                Modifier.padding(20.dp))
        }
        return
    }
    val battery = samples.map { it.battery }
    val out = samples.map { it.out.toFloat() }
    val inp = samples.map { it.inp.toFloat() }
    val maxPower = (out + inp).maxOrNull()?.coerceAtLeast(50f) ?: 50f

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Battery", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            LineChart(listOf(battery to MaterialTheme.colorScheme.primary), 0f, 100f,
                Modifier.fillMaxWidth().height(130.dp))
            Text("${battery.last().toInt()}% now · ${samples.size} samples",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Power (W)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            LineChart(
                listOf(
                    out to MaterialTheme.colorScheme.secondary,
                    inp to MaterialTheme.colorScheme.tertiary,
                ), 0f, maxPower, Modifier.fillMaxWidth().height(130.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendDot("Output", MaterialTheme.colorScheme.secondary)
                LegendDot("Input", MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

@Composable
private fun LineChart(
    series: List<Pair<List<Float>, Color>>, min: Float, max: Float, modifier: Modifier,
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier) {
        val range = (max - min).takeIf { it > 0f } ?: 1f
        drawLine(track, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 2f)
        series.forEach { (values, color) ->
            if (values.size < 2) return@forEach
            val dx = size.width / (values.size - 1)
            var prev = Offset(0f, size.height - ((values[0] - min) / range).coerceIn(0f, 1f) * size.height)
            for (i in 1 until values.size) {
                val x = i * dx
                val y = size.height - ((values[i] - min) / range).coerceIn(0f, 1f) * size.height
                drawLine(color, prev, Offset(x, y), strokeWidth = 3f)
                prev = Offset(x, y)
            }
        }
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(10.dp).background(color, RoundedCornerShape(2.dp)))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SignalMeter(rssi: Int?) {
    val bars = rssi?.let {
        when {
            it >= -55 -> 4
            it >= -67 -> 3
            it >= -78 -> 2
            it >= -90 -> 1
            else -> 0
        }
    } ?: 0
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        (1..4).forEach { i ->
            Box(
                Modifier
                    .width(4.dp)
                    .height((4 + i * 4).dp)
                    .background(
                        if (i <= bars) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(1.dp),
                    )
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(rssi?.let { "$it dBm" } ?: "—", style = MaterialTheme.typography.bodySmall)
    }
}
