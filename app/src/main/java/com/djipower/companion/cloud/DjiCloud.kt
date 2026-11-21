package com.djipower.companion.cloud

import org.json.JSONObject
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * One-time DJI account client: logs in and reads a station's local BLE pair_key.
 * Kotlin port of zuyan9/ha-dji-power-ble cloud.py. Used ONCE at onboarding; all
 * daily operation is local BLE and needs none of this.
 */
class DjiCloud(private val http: OkHttpClient = OkHttpClient()) {

    data class Device(val name: String, val sn: String, val pairUuid: String, val pairKey: String)

    class AuthException(val code: Int, msg: String) : Exception("DJI cloud error $code: $msg")
    class TwoFactorRequired(val code: Int) : Exception("two-step verification required ($code)")

    private val clientName = "android-1.5.16"
    private val deviceId = "dji-home-" + UUID.randomUUID().toString().replace("-", "").take(16)

    private companion object {
        const val ACCOUNT_BASE = "https://account.dji.com/apis/apprest/v1"
        val HOME_API_HOSTS = listOf(
            "https://home-api.djigate.com",
            "https://home-api-vg.djigate.com",
            "https://home-api-hz.djigate.com",
        )
        const val DEVICES_PATH = "/app/api/v1/users/devices/list"
        // Bootstrap HMAC key for Sign-Mc, recovered from the DJI Home app.
        const val SIGN_MC_KEY = "43421d0a-c0bf-4467-9542-3159cc6000cb"
        const val USER_AGENT = "DJIHome/1.5.16 (Android)"
        val TWO_FACTOR = setOf(553, 556)
        val RATE_LIMIT = setOf(508, 554)
    }

    private fun signedHeaders(): Map<String, String> {
        val ts = (System.currentTimeMillis() / 1000).toString()
        val invoke = "DeviceId-Mc$ts" + UUID.randomUUID().toString().replace("-", "").take(6)
        val material = "AppId-Mccr-appClientName-Mc$clientName" +
            "DeviceId-Mc$deviceId" + "InvokeId-Mc$invoke" + "Timestamp-Mc$ts"
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(SIGN_MC_KEY.toByteArray(), "HmacSHA1"))
        val sign = android.util.Base64.encodeToString(
            mac.doFinal(material.toByteArray()), android.util.Base64.NO_WRAP
        )
        return mapOf(
            "ClientName-Mc" to clientName, "DeviceId-Mc" to deviceId, "AppId-Mc" to "cr-app",
            "Timestamp-Mc" to ts, "InvokeId-Mc" to invoke, "Sign-Mc" to sign,
            "X-Risk-Version" to "1.0", "X-DJI-SDK-Version" to "1.0.0",
            "User-Agent" to USER_AGENT, "Accept" to "application/json",
        )
    }

    /** Fetch DJI's image captcha. Returns (srandom, pngBytes). */
    fun getImageCaptcha(): Pair<String, ByteArray> {
        val srandom = UUID.randomUUID().toString().replace("-", "")
        val b = Request.Builder().url("$ACCOUNT_BASE/vcode?srandom=$srandom")
        signedHeaders().forEach { (k, v) -> b.header(k, v) }
        http.newCall(b.get().build()).execute().use { r ->
            if (!r.isSuccessful) throw Exception("captcha request failed: ${r.code}")
            return srandom to (r.body?.bytes() ?: ByteArray(0))
        }
    }

    private fun post(action: String, form: Map<String, String>): JSONObject {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val b = Request.Builder().url("$ACCOUNT_BASE/$action").post(body)
        signedHeaders().forEach { (k, v) -> b.header(k, v) }
        b.header("Content-Type", "application/x-www-form-urlencoded")
        http.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw Exception("$action failed: ${r.code}")
            return JSONObject(r.body?.string() ?: "{}")
        }
    }

    fun exchangeCaptcha(srandom: String, code: String): String {
        val resp = post("validate_captcha", mapOf(
            "captchaType" to "imageCaptcha", "captchaModule" to "AppLogin",
            "verificationCode" to code, "srandom" to srandom,
        ))
        if (resp.optInt("code", -1) != 0) throw AuthException(resp.optInt("code"), resp.optString("message"))
        val ticket = resp.optJSONObject("data")?.optString("captchaTicket")
        if (ticket.isNullOrEmpty()) throw AuthException(0, "no captchaTicket")
        return ticket
    }

    /** Returns the member token. Throws TwoFactorRequired if a code is needed. */
    fun login(email: String, password: String, captchaTicket: String, emailCode: String? = null): String {
        val form = mutableMapOf("userName" to email, "password" to password, "captchaTicket" to captchaTicket)
        if (emailCode != null) { form["emailCode"] = emailCode; form["verificationCode"] = emailCode }
        val resp = post("user_login", form)
        val code = resp.optInt("code", -1)
        if (code in TWO_FACTOR) throw TwoFactorRequired(code)
        if (code in RATE_LIMIT) throw AuthException(code, "rate limited; wait and retry")
        if (code != 0) throw AuthException(code, resp.optString("message", "login failed"))
        val token = resp.optJSONObject("data")?.optString("token")
        if (token.isNullOrEmpty()) throw AuthException(code, "no member token in response")
        return token
    }

    /** List the account's devices that expose a pair_key. */
    fun listDevices(token: String): List<Device> {
        var lastErr: Exception? = null
        for (host in HOME_API_HOSTS) {
            try {
                val req = Request.Builder().url("$host$DEVICES_PATH")
                    .header("x-member-token", token)
                    .header("User-Agent", USER_AGENT).header("Accept", "application/json").get().build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) { lastErr = Exception("HTTP ${r.code}"); return@use }
                    val out = extractDevices(JSONObject(r.body?.string() ?: "{}"))
                    if (out.isNotEmpty()) return out
                }
            } catch (e: Exception) { lastErr = e }
        }
        lastErr?.let { throw it }
        return emptyList()
    }

    private fun extractDevices(payload: JSONObject): List<Device> {
        val out = ArrayList<Device>()
        val data = payload.optJSONObject("data") ?: return out
        for (key in listOf("dy_devices", "cr_devices")) {
            val arr = data.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val dev = arr.optJSONObject(i) ?: continue
                val base = dev.optJSONObject("base_info") ?: JSONObject()
                val pair = dev.optJSONObject("pair_info") ?: JSONObject()
                val pk = pair.optString("pair_key")
                if (pk.isNotEmpty()) out.add(Device(
                    base.optString("name", "DJI Power"), base.optString("sn"),
                    pair.optString("pair_uuid"), pk))
            }
        }
        return out
    }
}
