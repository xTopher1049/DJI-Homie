package com.djipower.companion.service

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Checks the US National Weather Service (api.weather.gov, free + keyless) for
 * active hurricane / tropical-storm / storm-surge alerts at a point. Runs on
 * the phone's own network, not the sandbox, so it reaches the API directly.
 */
object WeatherMonitor {
    private val client = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    private val QUALIFYING = listOf("hurricane", "tropical storm", "storm surge")

    /**
     * true  = a qualifying storm watch/warning is active for the point
     * false = checked, none active
     * null  = couldn't check (network/parse error) — caller should leave state as-is
     */
    fun stormActive(lat: Double, lon: Double): Boolean? {
        val url = "https://api.weather.gov/alerts/active?point=$lat,$lon"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "DJI-Homie/1.0 (github.com/xTopher1049/DJI-Homie)")
            .header("Accept", "application/geo+json")
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val features = JSONObject(body).optJSONArray("features") ?: return false
                for (i in 0 until features.length()) {
                    val props = features.optJSONObject(i)?.optJSONObject("properties") ?: continue
                    val event = props.optString("event").lowercase()
                    if (QUALIFYING.any { event.contains(it) }) return true
                }
                false
            }
        } catch (e: Exception) {
            null
        }
    }
}
