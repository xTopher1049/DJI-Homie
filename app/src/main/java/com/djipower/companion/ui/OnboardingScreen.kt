package com.djipower.companion.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
                Text("Type the characters shown (case-sensitive):")
                captchaBmp?.let { Image(it, contentDescription = "captcha") }
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
                        scanner.start(10_000) { scanResults = it }
                    }) { Text("Scan") }
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
                        credStore.save(mac.trim(), d.pairKey, d.name)
                        onDone()
                    }, modifier = Modifier.fillMaxWidth()
                ) { Text("Save & continue") }
            }
        }

        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
