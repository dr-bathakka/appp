package com.clukey.os

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.clukey.os.network.CloudSyncService
import com.clukey.os.utils.AppLogger
import com.clukey.os.utils.PrefsManager

/**
 * CluKeyApp — Application entry point.
 * Initialises global singletons: PrefsManager, AppLogger, CloudSyncService.
 * Creates all notification channels required by foreground services.
 */
class CluKeyApp : Application() {

    companion object {
        lateinit var instance: CluKeyApp
            private set

        const val CHANNEL_ASSISTANT = "clukey_assistant"
        const val CHANNEL_OVERLAY   = "clukey_overlay"
        const val CHANNEL_HTTP      = "clukey_http"
        const val CHANNEL_ALERTS    = "clukey_alerts"
        const val CHANNEL_DRIVING   = "clukey_driving"
        const val CHANNEL_FOCUS     = "clukey_focus"
        const val CHANNEL_TIMER     = "clukey_timer"
        const val CHANNEL_LOCATION  = "clukey_location"
        const val CHANNEL_HEALTH    = "clukey_health"
        const val CHANNEL_INTRUDER  = "clukey_intruder"
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        PrefsManager.init(this)
        AppLogger.init(this)
        CloudSyncService.init(this)
        createNotificationChannels()

        AppLogger.i("CluKeyOS", "Application started — v${BuildConfig.VERSION_NAME}")
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        listOf(
            NotificationChannel(CHANNEL_ASSISTANT, "CluKey Assistant", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Main AI assistant service" },
            NotificationChannel(CHANNEL_OVERLAY, "Floating Overlay", NotificationManager.IMPORTANCE_MIN)
                .apply { description = "Floating AI assistant bubble" },
            NotificationChannel(CHANNEL_HTTP, "HTTP Bridge", NotificationManager.IMPORTANCE_MIN)
                .apply { description = "Local phone control server" },
            NotificationChannel(CHANNEL_ALERTS, "CluKey Alerts", NotificationManager.IMPORTANCE_HIGH)
            NotificationChannel(CHANNEL_DRIVING, "Driving Mode", NotificationManager.IMPORTANCE_LOW)
            NotificationChannel(CHANNEL_FOCUS, "Focus Mode", NotificationManager.IMPORTANCE_LOW)
            NotificationChannel(CHANNEL_TIMER, "Timer", NotificationManager.IMPORTANCE_LOW)
            NotificationChannel(CHANNEL_LOCATION, "Location", NotificationManager.IMPORTANCE_MIN)
            NotificationChannel(CHANNEL_HEALTH, "Device Health", NotificationManager.IMPORTANCE_MIN)
            NotificationChannel(CHANNEL_INTRUDER, "Intruder Alert", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "AI assistant alerts and responses" },
        ).forEach { nm.createNotificationChannel(it) }
    }
}
