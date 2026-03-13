package com.clukey.os.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.clukey.os.utils.AppLogger
import com.clukey.os.utils.PrefsManager

/**
 * BootReceiver — starts AssistantService automatically after device boot.
 * Registered for BOOT_COMPLETED and QUICKBOOT_POWERON.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON") return

        AppLogger.i("BootReceiver", "Boot detected — starting CluKey assistant")

        // ✅ FIX: PrefsManager must be init'd here — CluKeyApp.onCreate() hasn't run yet at boot
        PrefsManager.init(context)

        if (!PrefsManager.autoStartEnabled) {
            AppLogger.i("BootReceiver", "Auto-start disabled — skipping")
            return
        }

        val serviceIntent = Intent(context, AssistantService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
