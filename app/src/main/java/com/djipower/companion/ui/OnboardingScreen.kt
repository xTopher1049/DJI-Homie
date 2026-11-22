package com.djipower.companion.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.djipower.companion.ble.DeviceScanner
import com.djipower.companion.cloud.DjiCloud
import com.djipower.companion.data.CredStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Step { CREDENTIALS, CAPTCHA, TWO_FACTOR, SELECT }

/**
 * First-run wizard: DJI account login (with DJI's image captcha) -> fetch the
 * station pair key -> scan BLE for its MAC -> save locally. The account is
 * never used again after this.
 */
@Composable
fun OnboardingScreen(credStore: CredStore, scanner: DeviceScanner, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val cloud = remember { DjiCloud() }

    var step by remember { mutableStateOf(Step.CREDENTIALS) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var srandom by remember { mutableStateOf("") }
    var captchaBmp by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var captchaCode by remember { mutableStateOf("") }
    var ticket by remember { mutableStateOf("") }
    var emailCode by remember { mutableStateOf("") }

    var token by remember { mutableStateOf("") }
    var devices by remember { mutableStateOf<List<DjiCloud.Device>>(emptyList()) }
    var chosenDevice by remember { mutableStateOf<DjiCloud.Device?>(null) }
    var scanResults by remember { mutableStateOf<List<DeviceScanner.Found>>(emptyList()) }
    var mac by remember { mutableStateOf("") }

    fun fail(t: Throwable) { error = t.message ?: t.toString(); busy = false }

    Column(
        Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Set up DJI Homie", style = MaterialTheme.typography.headlineSmall)
        Text("Sign in once to fetch your station's local key. Unofficial; not affiliated with DJI.",
            style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        when (step) {
            Step.CREDENTIALS -> {
                Text("DJI Homie talks to your station directly over Bluetooth. It needs your DJI " +
                    "login just once, to download the station's local pairing key. After that it " +
                    "works fully offline and never touches your account again. Your password is " +
                    "sent only to DJI and is never stored.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(email, { email = it }, label = { Text("DJI account email") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text("Password") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth())
                Button(enabled = !busy && email.isNotBlank() && password.isNotBlank(),
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            try {
                                val (sr, png) = withContext(Dispatchers.IO) { cloud.getImageCaptcha() }
                                srandom = sr
                                captchaBmp = BitmapFactory.decodeByteArray(png, 0, png.size)?.asImageBitmap()
                                step = Step.CAPTCHA; busy = false
                            } catch (t: Throwable) { fail(t) }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
            }

            Step.CAPTCHA -> {
                Text("DJI requires a captcha to sign in. Type the characters from the image " +
                    "below exactly — it is case-sensitive.",
                    style = MaterialTheme.typography.bodySmall)
                captchaBmp?.let {
                    Image(
                        bitmap = it,
                        contentDescription = "captcha",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(96.dp)
                            .background(Color.White)
                            .padding(6.dp),
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.None,
                    )
                }
                OutlinedTextField(captchaCode, { captchaCode = it }, label = { Text("Captcha") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(enabled = !busy && captchaCode.isNotBlank(), onClick = {
                    busy = true; error = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                ticket = cloud.exchangeCaptcha(srandom, captchaCode.trim())
                                token = cloud.login(email, password, ticket)
                                devices = cloud.listDevices(token)
                            }
                            chosenDevice = devices.firstOrNull()
                            step = Step.SELECT; busy = false
                        } catch (tf: DjiCloud.TwoFactorRequired) {
                            step = Step.TWO_FACTOR; busy = false
                        } catch (t: Throwable) { fail(t) }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Log in") }
            }

            Step.TWO_FACTOR -> {
                Text("Your DJI account has two-step verification turned on, so DJI emailed you a " +
                    "one-time code.", style = MaterialTheme.typography.bodySmall)
                Text("Enter the verification code DJI emailed you:")
                OutlinedTextField(emailCode, { emailCode = it }, label = { Text("Email code") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(enabled = !busy && emailCode.isNotBlank(), onClick = {
                    busy = true; error = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                token = cloud.login(email, password, ticket, emailCode.trim())
                                devices = cloud.listDevices(token)
                            }
                            chosenDevice = devices.firstOrNull()
                            step = Step.SELECT; busy = false
                        } catch (t: Throwable) { fail(t) }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Verify") }
            }

            Step.SELECT -> {
                Text("Pair key retrieved. Now pick your station.",
                    style = MaterialTheme.typography.titleMedium)
                Text("DJI Homie is scanning for your powered-on station nearby over Bluetooth. " +
                    "Tap it when it appears, or type its MAC address.",
                    style = MaterialTheme.typography.bodySmall)
                LaunchedEffect(Unit) {
                    scanResults = emptyList()
                    scanner.start(12_000) { scanResults = it }
                }
                if (devices.size > 1) {
                    Text("Account devices:")
                    devices.forEach { d ->
                        val sel = d == chosenDevice
                        OutlinedButton(onClick = { chosenDevice = d }, modifier = Modifier.fillMaxWidth()) {
                            Text((if (sel) "✓ " else "") + "${d.name}  ·  ${d.sn}")
                        }
                    }
                }

                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Nearby stations (Bluetooth):")
                    TextButton(onClick = {
                        scanResults = emptyList()
                        scanner.start(12_000) { scanResults = it }
                    }) { Text("Rescan") }
                }
                scanResults.forEach { f ->
                    OutlinedButton(onClick = { mac = f.address }, modifier = Modifier.fillMaxWidth()) {
                        Text((if (mac == f.address) "✓ " else "") + "${f.name}  ·  ${f.address}")
                    }
                }
                OutlinedTextField(mac, { mac = it.trim() }, label = { Text("Station MAC") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())

                Button(
                    enabled = chosenDevice != null && mac.matches(Regex("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")),
                    onClick = {
                        val d = chosenDevice!!
                        val model = scanResults
                            .firstOrNull { it.address.equals(mac.trim(), ignoreCase = true) }
                            ?.model ?: com.djipower.companion.protocol.Models.DEFAULT
                        credStore.save(mac.trim(), d.pairKey, d.name, model)
                        onDone()
                    }, modifier = Modifier.fillMaxWidth()
                ) { Text("Save & continue") }
            }
        }

        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
