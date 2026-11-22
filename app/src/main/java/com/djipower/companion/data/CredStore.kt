package com.djipower.companion.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.djipower.companion.protocol.Models

/**
 * Encrypted storage for the station's local credentials. The pair key never
 * leaves the device; it is written once at onboarding and read for each connect.
 */
class CredStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "dji_homie_creds",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var mac: String?
        get() = prefs.getString("mac", null)
        set(v) = prefs.edit().putString("mac", v).apply()

    var pairKey: String?
        get() = prefs.getString("pair_key", null)
        set(v) = prefs.edit().putString("pair_key", v).apply()

    var stationName: String?
        get() = prefs.getString("name", null)
        set(v) = prefs.edit().putString("name", v).apply()

    /** Detected station model; defaults to the original Power 1000 for pre-existing setups. */
    var model: String
        get() = prefs.getString("model", Models.DEFAULT) ?: Models.DEFAULT
        set(v) = prefs.edit().putString("model", v).apply()

    var themeMode: String
        get() = prefs.getString("theme_mode", "auto") ?: "auto"
        set(v) = prefs.edit().putString("theme_mode", v).apply()

    val isOnboarded: Boolean
        get() = !mac.isNullOrBlank() && (pairKey?.length == 32)

    fun save(mac: String, pairKey: String, name: String?, model: String = Models.DEFAULT) {
        prefs.edit()
            .putString("mac", mac)
            .putString("pair_key", pairKey)
            .putString("name", name)
            .putString("model", model)
            .apply()
    }

    fun clear() = prefs.edit().clear().apply()
}
