package com.clukey.os.service

import android.content.Context
import com.clukey.os.utils.AppLogger
import com.clukey.os.utils.PrefsManager

object FocusModeManager {
    private const val TAG = "FocusModeManager"
    private var currentMode = "off"

    fun init(context: Context) {
        currentMode = PrefsManager.focusMode
    }

    fun startFocusMode(mode: String): String {
        currentMode = mode
        PrefsManager.focusMode = mode
        AppLogger.i(TAG, "Focus mode: $mode")
        return when (mode) {
            "study" -> "📚 Study mode on."
            "work"  -> "💼 Work mode on."
            "sleep" -> "🌙 Sleep mode on."
            "off"   -> "✅ Focus mode off."
            else    -> "Focus mode: $mode."
        }
    }

    fun getStatus(): String = if (currentMode == "off") "Focus mode is off." else "Focus mode: $currentMode."
    fun getMode(): String = currentMode
    fun isActive(): Boolean = currentMode != "off"
}
