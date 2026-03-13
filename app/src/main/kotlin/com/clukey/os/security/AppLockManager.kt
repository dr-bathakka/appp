package com.clukey.os.security

import android.content.Context
import android.content.SharedPreferences
import com.clukey.os.utils.AppLogger
import com.clukey.os.utils.PrefsManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * AppLockManager — manages per-app PIN lock, session unlocks, and intruder logging.
 */
object AppLockManager {

    private const val TAG = "AppLockManager"
    private lateinit var prefs: SharedPreferences

    // Per-app lock state
    private val lockedApps = mutableSetOf<String>()
    private val sessionUnlocked = ConcurrentHashMap<String, Long>() // pkg -> unlock time

    // Intruder log
    private val intruderLog = mutableListOf<JSONObject>()
    var failedAttempts: Int = 0
        private set

    // Master switch
    var masterLockEnabled: Boolean = false
        private set

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("clukey_applock", Context.MODE_PRIVATE)
        masterLockEnabled = PrefsManager.appLockPin.isNotBlank()
        // Load locked apps
        lockedApps.clear()
        val saved = prefs.getString("locked_apps", "[]") ?: "[]"
        try {
            val arr = JSONArray(saved)
            for (i in 0 until arr.length()) lockedApps.add(arr.getString(i))
        } catch (_: Exception) {}
        AppLogger.i(TAG, "AppLockManager init — locked=${lockedApps.size} apps, master=$masterLockEnabled")
    }

    // ── Lock management ───────────────────────────────────────────────────────

    fun lockApp(pkg: String) {
        lockedApps.add(pkg.trim())
        saveLockedApps()
        AppLogger.i(TAG, "Locked app: $pkg")
    }

    fun unlockApp(pkg: String) {
        lockedApps.remove(pkg.trim())
        sessionUnlocked.remove(pkg.trim())
        saveLockedApps()
        AppLogger.i(TAG, "Unlocked app: $pkg")
    }

    fun isAppLocked(pkg: String): Boolean = masterLockEnabled && lockedApps.contains(pkg)
    fun isLocked(pkg: String): Boolean = isAppLocked(pkg) // alias

    fun isSessionUnlocked(pkg: String): Boolean {
        val t = sessionUnlocked[pkg] ?: return false
        // Session unlock expires after 30 minutes
        return (System.currentTimeMillis() - t) < 30 * 60 * 1000L
    }

    fun sessionUnlock(pkg: String) {
        sessionUnlocked[pkg] = System.currentTimeMillis()
    }

    fun getLockedApps(): Set<String> = lockedApps.toSet()

    // ── PIN verification ──────────────────────────────────────────────────────

    fun verifyPin(pin: String): Boolean {
        val correct = pin == PrefsManager.appLockPin
        if (!correct) {
            failedAttempts++
            AppLogger.w(TAG, "Wrong PIN attempt #$failedAttempts")
        } else {
            failedAttempts = 0
        }
        return correct
    }

    fun setPin(pin: String) {
        PrefsManager.appLockPin = pin
        masterLockEnabled = pin.isNotBlank()
    }

    fun isEnabled() = masterLockEnabled
    fun setEnabled(on: Boolean) { masterLockEnabled = on }

    // ── Intruder logging ──────────────────────────────────────────────────────

    fun logIntruder(photoPath: String? = null, reason: String = "wrong_pin") {
        val entry = JSONObject().apply {
            put("time", SimpleDateFormat("HH:mm:ss dd/MM", Locale.getDefault()).format(Date()))
            put("reason", reason)
            put("photo_path", photoPath ?: "")
            put("failed_attempts", failedAttempts)
        }
        intruderLog.add(entry)
        if (intruderLog.size > 50) intruderLog.removeAt(0)
        AppLogger.w(TAG, "Intruder logged: $reason")
    }

    fun getIntruderLog(): List<JSONObject> = intruderLog.toList()

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun saveLockedApps() {
        if (!::prefs.isInitialized) return
        prefs.edit().putString("locked_apps", JSONArray(lockedApps.toList()).toString()).apply()
    }
}
