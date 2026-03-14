package com.clukey.os.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import com.clukey.os.CluKeyApp
import com.clukey.os.R
import com.clukey.os.network.CloudSyncService
import com.clukey.os.utils.AppLogger
import kotlinx.coroutines.*

/**
 * DeviceHealthService — pushes battery %, RAM, charging state to server every 30s.
 * Server stores it → dashboard shows live Device Health panel.
 */
class DeviceHealthService : Service() {

    private val TAG = "DeviceHealthService"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val INTERVAL = 30_000L

    override fun onCreate() {
        super.onCreate()
        startForeground(2002, buildNotification())
        AppLogger.i(TAG, "DeviceHealthService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch {
            while (isActive) {
                try { pushHealth() } catch (e: Exception) { AppLogger.e(TAG, "health push failed", e) }
                delay(INTERVAL)
            }
        }
        return START_STICKY
    }

    private suspend fun pushHealth() {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val battery  = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging

        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val ramTotal = memInfo.totalMem / (1024 * 1024)
        val ramUsed  = ramTotal - (memInfo.availMem / (1024 * 1024))

        CloudSyncService.pushDeviceHealth(battery, charging, ramUsed, ramTotal)
        AppLogger.i(TAG, "Health pushed: battery=$battery% charging=$charging RAM=${ramUsed}/${ramTotal}MB")
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CluKeyApp.CHANNEL_ASSISTANT)
            .setSmallIcon(R.drawable.ic_clukey_notif)
            .setContentTitle("CluKey")
            .setContentText("Monitoring device health…")
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
