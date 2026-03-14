package com.clukey.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.*
import androidx.core.app.NotificationCompat
import com.clukey.app.network.CloudSyncService
import org.json.JSONObject
import java.util.*

class DeviceHealthService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val INTERVAL = 30_000L // 30 seconds
    private val CHANNEL_ID = "device_health"

    private val healthRunnable = object : Runnable {
        override fun run() {
            pushHealthData()
            handler.postDelayed(this, INTERVAL)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(2, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handler.post(healthRunnable)
        return START_STICKY
    }

    private fun pushHealthData() {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging = bm.isCharging

        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val totalRam = memInfo.totalMem / (1024 * 1024)
        val availRam = memInfo.availMem / (1024 * 1024)
        val usedRam = totalRam - availRam

        val data = JSONObject().apply {
            put("battery", batteryLevel)
            put("charging", isCharging)
            put("ram_total_mb", totalRam)
            put("ram_used_mb", usedRam)
            put("ram_available_mb", availRam)
            put("timestamp", System.currentTimeMillis())
        }

        CloudSyncService(this).pushDeviceHealth(data)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Device Health", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CluKey")
            .setContentText("Monitoring device health...")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(healthRunnable)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
