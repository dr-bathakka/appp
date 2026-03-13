package com.clukey.os.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.clukey.os.utils.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ScreenReaderService — AccessibilityService for reading UI elements
 * and simulating taps (VisualControlEngine functionality).
 *
 * Capabilities:
 *   • Read all on-screen text
 *   • Find buttons/views by text or content description
 *   • Simulate tap at coordinates
 *   • Track foreground app changes
 *
 * Requires Accessibility permission in Android Settings.
 */
class ScreenReaderService : AccessibilityService() {

    companion object {
        private const val TAG = "ScreenReader"
        var instance: ScreenReaderService? = null
            private set

        private val _screenText = MutableStateFlow("")
        val screenText: StateFlow<String> = _screenText

        private val _foregroundApp = MutableStateFlow("")
        val foregroundApp: StateFlow<String> = _foregroundApp
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AppLogger.i(TAG, "AccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                _foregroundApp.value = pkg
                AppLogger.d(TAG, "Foreground app: $pkg")
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                refreshScreenText()
            }
        }
    }

    override fun onInterrupt() {
        AppLogger.w(TAG, "AccessibilityService interrupted")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ── Screen text extraction ────────────────────────────────────────────────

    fun refreshScreenText() {
        val root = rootInActiveWindow ?: return
        val sb = StringBuilder()
        extractText(root, sb)
        _screenText.value = sb.toString().trim()
    }

    private fun extractText(node: AccessibilityNodeInfo, sb: StringBuilder) {
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrBlank()) sb.appendLine(text)
        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrBlank() && desc != text) sb.appendLine(desc)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { extractText(it, sb) }
        }
    }

    // ── Element finding ───────────────────────────────────────────────────────

    fun findNodeByText(text: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findByText(root, text)
    }

    private fun findByText(node: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        val nodeText = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
        if (nodeText.contains(target, ignoreCase = true)) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let {
                val found = findByText(it, target)
                if (found != null) return found
            }
        }
        return null
    }

    // ── Tap simulation ────────────────────────────────────────────────────────

    fun tapNode(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun tapAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    fun tapByText(text: String): Boolean {
        val node = findNodeByText(text) ?: run {
            AppLogger.w(TAG, "Node '$text' not found on screen")
            return false
        }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        AppLogger.i(TAG, "[TAP] '$text' at (${rect.centerX()}, ${rect.centerY()})")
        return tapAt(rect.centerX().toFloat(), rect.centerY().toFloat())
    }

    // ── Back / Home navigation ────────────────────────────────────────────────

    fun goBack()  = performGlobalAction(GLOBAL_ACTION_BACK)
    fun goHome()  = performGlobalAction(GLOBAL_ACTION_HOME)
    fun openRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)
}
