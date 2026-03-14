package com.clukey.os.utils

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

object PrefsManager {
    private const val PREFS_NAME = "clukey_prefs"
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    val isInitialized get() = ::prefs.isInitialized

    var serverUrl: String
        get() = prefs.getString("server_url", "http://10.0.2.2:5000") ?: "http://10.0.2.2:5000"
        set(v) = prefs.edit().putString("server_url", v).apply()

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(v) = prefs.edit().putString("api_key", v).apply()

    var deviceId: String
        get() {
            var id = prefs.getString("device_id", "") ?: ""
            if (id.isBlank()) { id = UUID.randomUUID().toString(); prefs.edit().putString("device_id", id).apply() }
            return id
        }
        set(v) = prefs.edit().putString("device_id", v).apply()

    var isRegistered: Boolean
        get() = prefs.getBoolean("is_registered", false)
        set(v) = prefs.edit().putBoolean("is_registered", v).apply()

    var wakeWord: String
        get() = prefs.getString("wake_word", "clukey") ?: "clukey"
        set(v) = prefs.edit().putString("wake_word", v).apply()

    var wakeWordEnabled: Boolean
        get() = prefs.getBoolean("wake_word_enabled", true)
        set(v) = prefs.edit().putBoolean("wake_word_enabled", v).apply()

    var httpServerEnabled: Boolean
        get() = prefs.getBoolean("http_server_enabled", true)
        set(v) = prefs.edit().putBoolean("http_server_enabled", v).apply()

    var httpServerPort: Int
        get() = prefs.getInt("http_server_port", 8080)
        set(v) = prefs.edit().putInt("http_server_port", v).apply()

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean("notifications_enabled", true)
        set(v) = prefs.edit().putBoolean("notifications_enabled", v).apply()

    var catchAllNotificationsEnabled: Boolean
        get() = prefs.getBoolean("catch_all_notifications", false)
        set(v) = prefs.edit().putBoolean("catch_all_notifications", v).apply()

    var locationEnabled: Boolean
        get() = prefs.getBoolean("location_enabled", false)
        set(v) = prefs.edit().putBoolean("location_enabled", v).apply()

    var appLockPin: String
        get() = prefs.getString("app_lock_pin", "") ?: ""
        set(v) = prefs.edit().putString("app_lock_pin", v).apply()

    var focusMode: String
        get() = prefs.getString("focus_mode", "off") ?: "off"
        set(v) = prefs.edit().putString("focus_mode", v).apply()

    var lastKnownSim: String
        get() = prefs.getString("last_sim", "") ?: ""
        set(v) = prefs.edit().putString("last_sim", v).apply()

    var autoStartEnabled: Boolean
        get() = prefs.getBoolean("auto_start", true)
        set(v) = prefs.edit().putBoolean("auto_start", v).apply()

    var drivingModeAutoEnabled: Boolean
        get() = prefs.getBoolean("driving_mode_auto", false)
        set(v) = prefs.edit().putBoolean("driving_mode_auto", v).apply()

    var longPollIntervalMs: Long
        get() = prefs.getLong("long_poll_interval_ms", 5000L)
        set(v) = prefs.edit().putLong("long_poll_interval_ms", v).apply()
}
