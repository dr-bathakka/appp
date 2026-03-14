package com.clukey.os.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.clukey.os.utils.AppLogger
import com.clukey.os.utils.PrefsManager
import kotlinx.coroutines.*

object FocusModeManager {

    private const val PREFS = "clukey_focus"
    private var prefs: android.content.SharedPreferences? = null

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    var activeFocusMode: String
        get() = prefs?.getString("focus_mode", "off") ?: "off"
        set(v) = prefs?.edit()?.putString("focus_mode", v)?.apply() ?: Unit

    var focusStartTime: Long
        get() = prefs?.getLong("focus_start", 0L) ?: 0L
        set(v) = prefs?.edit()?.putLong("focus_start", v)?.apply() ?: Unit

    val FOCUS_MODES = mapOf(
        "study" to listOf("com.zhiliaoapp.musically", "com.instagram.android",
            "com.twitter.android", "com.snapchat.android", "com.reddit.frontpage",
            "com.facebook.katana", "com.google.android.youtube"),
        "work" to listOf("com.zhiliaoapp.musically", "com.instagram.android",
            "com.snapchat.android", "com.reddit.frontpage", "com.pinterest"),
        "sleep" to listOf("com.zhiliaoapp.musically", "com.instagram.android",
            "com.twitter.android", "com.snapchat.android", "com.reddit.frontpage",
            "com.facebook.katana", "com.google.android.youtube",
            "com.discord", "com.facebook.orca")
    )

    fun isAppBlocked(packageName: String): Boolean {
        val mode = activeFocusMode
        if (mode == "off") return false
        return FOCUS_MODES[mode]?.contains(packageName) == true ||
               getCustomBlockedApps().contains(packageName)
    }

    fun getCustomBlockedApps(): Set<String> {
        return prefs?.getStringSet("custom_blocked", emptySet()) ?: emptySet()
    }

    fun addCustomBlockedApp(pkg: String) {
        val set = getCustomBlockedApps().toMutableSet()
        set.add(pkg)
        prefs?.edit()?.putStringSet("custom_blocked", set)?.apply()
    }

    fun removeCustomBlockedApp(pkg: String) {
        val set = getCustomBlockedApps().toMutableSet()
        set.remove(pkg)
        prefs?.edit()?.putStringSet("custom_blocked", set)?.apply()
    }

    fun setAppBudgetMinutes(packageName: String, minutes: Int) {
        prefs?.edit()?.putInt("budget_$packageName", minutes)?.apply()
    }

    fun getAppBudgetMinutes(packageName: String): Int {
        return prefs?.getInt("budget_$packageName", -1) ?: -1
    }

    fun getAppUsageTodayMinutes(packageName: String): Int {
        return prefs?.getInt("usage_today_$packageName", 0) ?: 0
    }

    fun updateAppUsage(packageName: String, addMinutes: Int) {
        val current = getAppUsageTodayMinutes(packageName)
        prefs?.edit()?.putInt("usage_today_$packageName", current + addMinutes)?.apply()
    }

    fun isBudgetExceeded(packageName: String): Boolean {
        val budget = getAppBudgetMinutes(packageName)
        if (budget <= 0) return false
        return getAppUsageTodayMinutes(packageName) >= budget
    }

    fun startFocusMode(mode: String): String {
        activeFocusMode = mode
        focusStartTime = System.currentTimeMillis()
        return when (mode) {
            "study" -> "📚 Study mode ON. Blocking distracting apps."
            "work"  -> "💼 Work mode ON. Stay productive!"
            "sleep" -> "😴 Sleep mode ON. Put the phone down!"
            "off"   -> { activeFocusMode = "off"; "✅ Focus mode disabled." }
            else    -> "Unknown mode: $mode"
        }
    }

    fun getFocusTimeMinutes(): Int {
        if (activeFocusMode == "off") return 0
        return ((System.currentTimeMillis() - focusStartTime) / 60000).toInt()
    }

    fun getStatus(): String {
        if (activeFocusMode == "off") return "Focus mode is OFF"
        val mins = getFocusTimeMinutes()
        val blockedCount = (FOCUS_MODES[activeFocusMode]?.size ?: 0) + getCustomBlockedApps().size
        return "📵 ${activeFocusMode.uppercase()} MODE — ${mins}min active. $blockedCount apps blocked."
    }

    fun isActive(): Boolean = activeFocusMode != "off"
    fun getMode(): String = activeFocusMode
}

class FocusModeService : Service() {

    companion object {
        const val CHANNEL_ID = "clukey_focus"
        const val NOTIF_ID = 2003
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        FocusModeManager.init(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = intent?.getStringExtra("mode") ?: "study"
        FocusModeManager.startFocusMode(mode)
        startForeground(NOTIF_ID, buildNotification(mode))

        scope.launch {
            while (isActive) {
                delay(60_000)
                checkBudgetAlerts()
            }
        }
        return START_STICKY
    }

    private fun checkBudgetAlerts() {
        val budgetApps = listOf(
            "com.zhiliaoapp.musically" to "TikTok",
            "com.instagram.android"    to "Instagram",
            "com.google.android.youtube" to "YouTube",
            "com.twitter.android"      to "Twitter"
        )
        for ((pkg, name) in budgetApps) {
            if (FocusModeManager.isBudgetExceeded(pkg)) {
                val used = FocusModeManager.getAppUsageTodayMinutes(pkg)
                AppLogger.w("FocusMode", "⚠️ Budget exceeded for $name")
                pushAlert(name, used)
            }
        }
    }

    private fun pushAlert(appName: String, usageMinutes: Int) {
        scope.launch(Dispatchers.IO) {
            try {
                val serverUrl = PrefsManager.serverUrl.ifBlank { return@launch }
                val payload = org.json.JSONObject().apply {
                    put("type", "usage_budget")
                    put("app", appName)
                    put("minutes_used", usageMinutes)
                    put("message", "⚠️ You've used $appName for ${usageMinutes / 60}h ${usageMinutes % 60}min today")
                }
                val url = java.net.URL("$serverUrl/alerts/usage")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"; conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 3000
                conn.outputStream.use { it.write(payload.toString().toByteArray()) }
                conn.responseCode; conn.disconnect()
            } catch (_: Exception) {}
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "CluKey Focus Mode", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NotificationManager::class.java))?.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(mode: String): Notification {
        val emoji = when (mode) { "study" -> "📚"; "work" -> "💼"; "sleep" -> "😴"; else -> "📵" }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("$emoji Focus Mode: ${mode.uppercase()}")
            .setContentText(FocusModeManager.getStatus())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        FocusModeManager.startFocusMode("off")
    }
}
