package com.clukey.os.engine

import com.clukey.os.service.ScreenReaderService
import com.clukey.os.utils.AppLogger

/**
 * VisualControlEngine — uses ScreenReaderService (AccessibilityService) to
 * detect on-screen UI elements and simulate taps.
 *
 * Examples:
 *   tap("Like")    → finds Like button and taps it
 *   tap("Send")    → finds Send button and taps it
 *   tapAt(540f, 1200f) → taps exact coordinates
 *   readScreen()   → returns all visible text
 */
object VisualControlEngine {

    private const val TAG = "VisualControl"

    fun tapByText(elementText: String): Boolean {
        val svc = ScreenReaderService.instance
        if (svc == null) {
            AppLogger.w(TAG, "AccessibilityService not connected — tap skipped")
            return false
        }
        val success = svc.tapByText(elementText)
        AppLogger.i(TAG, if (success) "Tapped '$elementText'" else "Element '$elementText' not found")
        return success
    }

    fun tapAt(x: Float, y: Float): Boolean {
        val svc = ScreenReaderService.instance ?: return false
        AppLogger.i(TAG, "Tap at ($x, $y)")
        return svc.tapAt(x, y)
    }

    fun readScreen(): String {
        val svc = ScreenReaderService.instance
        if (svc == null) {
            AppLogger.w(TAG, "AccessibilityService not connected")
            return ""
        }
        svc.refreshScreenText()
        return ScreenReaderService.screenText.value
    }

    fun goBack()  = ScreenReaderService.instance?.goBack()  ?: false
    fun goHome()  = ScreenReaderService.instance?.goHome()  ?: false
    fun openRecents() = ScreenReaderService.instance?.openRecents() ?: false

    /**
     * Executes a named action on the current screen.
     * Called by CommandExecutor when cloud AI returns action="tap:Like" etc.
     */
    fun executeAction(action: String): Boolean {
        val parts = action.split(":")
        return when (parts.firstOrNull()) {
            "tap"      -> tapByText(parts.getOrElse(1) { "" })
            "tap_at"   -> {
                val x = parts.getOrElse(1) { "0" }.toFloatOrNull() ?: 0f
                val y = parts.getOrElse(2) { "0" }.toFloatOrNull() ?: 0f
                tapAt(x, y)
            }
            "back"     -> { goBack(); true }
            "home"     -> { goHome(); true }
            "recents"  -> { openRecents(); true }
            else       -> { AppLogger.w(TAG, "Unknown action: $action"); false }
        }
    }
}
