package com.clukey.os.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.clukey.os.utils.AppLogger
import com.clukey.os.utils.PrefsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Safety: init PrefsManager in case Application.onCreate() hasn't run yet
        try { PrefsManager.init(applicationContext) } catch (_: Exception) {}
        try { AppLogger.init(applicationContext) } catch (_: Exception) {}

        instance = this

        // Configure service info programmatically (more reliable than XML-only)
        serviceInfo = serviceInfo?.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                         AccessibilityEvent.TYPE_VIEW_SCROLLED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }

        AppLogger.i(TAG, "AccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    val pkg = event.packageName?.toString() ?: return
                    _foregroundApp.value = pkg
                }
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                    refreshScreenText()
                }
            }
        } catch (_: Exception) {}
    }

    override fun onInterrupt() {
        AppLogger.w(TAG, "AccessibilityService interrupted")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun refreshScreenText() {
        try {
            val root = rootInActiveWindow ?: return
            val sb = StringBuilder()
            extractText(root, sb)
            _screenText.value = sb.toString().trim()
        } catch (_: Exception) {}
    }

    private fun extractText(node: AccessibilityNodeInfo, sb: StringBuilder) {
        try {
            val text = node.text?.toString()?.trim()
            if (!text.isNullOrBlank()) sb.appendLine(text)
            val desc = node.contentDescription?.toString()?.trim()
            if (!desc.isNullOrBlank() && desc != text) sb.appendLine(desc)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { extractText(it, sb) }
            }
        } catch (_: Exception) {}
    }

    fun findNodeByText(text: String): AccessibilityNodeInfo? {
        return try {
            val root = rootInActiveWindow ?: return null
            findByText(root, text)
        } catch (_: Exception) { null }
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

    fun tapNode(node: AccessibilityNodeInfo): Boolean =
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)

    fun tapAt(x: Float, y: Float): Boolean {
        return try {
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 100)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        } catch (_: Exception) { false }
    }

    fun tapByText(text: String): Boolean {
        val node = findNodeByText(text) ?: return false
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return tapAt(rect.centerX().toFloat(), rect.centerY().toFloat())
    }

    fun goBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun goHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun openRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)
}
