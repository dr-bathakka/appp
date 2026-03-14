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

object AppLockManager {

    private const val TAG = "AppLockManager"
    private lateinit var prefs: SharedPreferences

    private val lockedApps = mutableSetOf<String>()
    private val sessionUnlocked = ConcurrentHashMap<String, Long>()
    private val intruderLog = mutableListOf<JSONObject>()

    var failedAttempts: Int = 0
        private set

    var masterLockEnabled: Boolean = false
        private set

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("clukey_applock", Context.MODE_PRIVATE)
        masterLockEnabled = PrefsManager.appLockPin.isNotBlank()
        lockedApps.clear()
        val saved = prefs.getString("locked_apps", "[]") ?: "[]"
        try {
            val arr = JSONArray(saved)
            for (i in 0 until arr.length()) lockedApps.add(arr.getString(i))
        } catch (_: Exception) {}
        AppLogger.i(TAG, "AppLockManager init — locked=${lockedApps.size}, master=$masterLockEnabled")
    }

    fun lockApp(pkg: String) {
        lockedApps.add(pkg.trim())
        saveLockedApps()
    }

    fun unlockApp(pkg: String) {
        lockedApps.remove(pkg.trim())
        sessionUnlocked.remove(pkg.trim())
        saveLockedApps()
    }

    fun isAppLocked(pkg: String): Boolean = masterLockEnabled && lockedApps.contains(pkg)
    fun isLocked(pkg: String): Boolean = isAppLocked(pkg)

    fun isSessionUnlocked(pkg: String): Boolean {
        val t = sessionUnlocked[pkg] ?: return false
        return (System.currentTimeMillis() - t) < 30 * 60 * 1000L
    }

    fun sessionUnlock(pkg: String) {
        sessionUnlocked[pkg] = System.currentTimeMillis()
    }

    fun getLockedApps(): Set<String> = lockedApps.toSet()

    fun verifyPin(pin: String): Boolean {
        val correct = pin == PrefsManager.appLockPin
        if (!correct) { failedAttempts++; AppLogger.w(TAG, "Wrong PIN #$failedAttempts") }
        else failedAttempts = 0
        return correct
    }

    fun setPin(pin: String) {
        PrefsManager.appLockPin = pin
        masterLockEnabled = pin.isNotBlank()
    }

    fun isEnabled() = masterLockEnabled
    fun setEnabled(on: Boolean) { masterLockEnabled = on }

    fun logIntruder(photoPath: String? = null, reason: String = "wrong_pin") {
        val entry = JSONObject().apply {
            put("time", SimpleDateFormat("HH:mm:ss dd/MM", Locale.getDefault()).format(Date()))
            put("reason", reason)
            put("photo_path", photoPath ?: "")
            put("failed_attempts", failedAttempts)
        }
        intruderLog.add(entry)
        if (intruderLog.size > 50) intruderLog.removeAt(0)
    }

    fun getIntruderLog(): List<JSONObject> = intruderLog.toList()

    private fun saveLockedApps() {
        if (!::prefs.isInitialized) return
        prefs.edit().putString("locked_apps", JSONArray(lockedApps.toList()).toString()).apply()
    }
}
