package com.clukey.app.service

import android.app.*
import android.content.*
import android.media.AudioManager
import android.os.*
import androidx.core.app.NotificationCompat
import com.clukey.app.network.CloudSyncService
import org.json.JSONArray
import org.json.JSONObject

class RulesTickService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val INTERVAL = 60_000L // Every 60 seconds
    private val CHANNEL_ID = "rules_service"

    private val tickRunnable = object : Runnable {
        override fun run() {
            evaluateRules()
            handler.postDelayed(this, INTERVAL)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(5, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handler.post(tickRunnable)
        return START_STICKY
    }

    private fun evaluateRules() {
        CloudSyncService(this).getRules { rules ->
            for (i in 0 until rules.length()) {
                val rule = rules.getJSONObject(i)
                if (shouldTrigger(rule)) {
                    executeAction(rule.getString("action"), rule.optJSONObject("params"))
                }
            }
        }
    }

    private fun shouldTrigger(rule: JSONObject): Boolean {
        val condition = rule.getString("condition")
        val value = rule.optInt("value", 0)

        return when (condition) {
            "battery_below" -> getBatteryLevel() < value
            "battery_above" -> getBatteryLevel() > value
            "time_is" -> getCurrentHour() == value
            else -> false
        }
    }

    private fun executeAction(action: String, params: JSONObject?) {
        when (action) {
            "silent_mode" -> setSilentMode(true)
            "normal_mode" -> setSilentMode(false)
            "notify_pc" -> CloudSyncService(this).pushAlert(
                JSONObject().apply {
                    put("type", "rule_triggered")
                    put("message", params?.optString("message", "Rule triggered") ?: "Rule triggered")
                }
            )
            "lock_app" -> {
                // App lock via notification
                val appName = params?.optString("app", "App") ?: "App"
                CloudSyncService(this).pushAlert(
                    JSONObject().apply {
                        put("type", "app_limit")
                        put("message", "$appName usage limit reached")
                    }
                )
            }
        }
    }

    private fun setSilentMode(silent: Boolean) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.ringerMode = if (silent)
            AudioManager.RINGER_MODE_SILENT
        else
            AudioManager.RINGER_MODE_NORMAL
    }

    private fun getBatteryLevel(): Int {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun getCurrentHour(): Int {
        return java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Automation Rules", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CluKey Automation")
            .setContentText("Rules engine running...")
            .setSmallIcon(android.R.drawable.ic_menu_preferences)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tickRunnable)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
