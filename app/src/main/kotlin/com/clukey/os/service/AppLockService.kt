package com.clukey.os.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.clukey.os.security.AppLockManager
import com.clukey.os.ui.AppLockActivity
import com.clukey.os.utils.AppLogger

/**
 * AppLockService — Accessibility service that monitors foreground app.
 * When a locked app is detected, launches AppLockActivity over it.
 */
class AppLockService : AccessibilityService() {

    companion object {
        const val TAG = "AppLockService"
        var instance: AppLockService? = null
    }

    private var lastPackage = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        AppLogger.i(TAG, "AppLockService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg == "com.clukey.os" || pkg == packageName) return
        if (pkg == lastPackage) return
        lastPackage = pkg

        if (!AppLockManager.masterLockEnabled) return
        if (!AppLockManager.isAppLocked(pkg)) return
        if (AppLockManager.isSessionUnlocked(pkg)) return

        val appName = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }

        AppLogger.i(TAG, "Locked app detected: $appName ($pkg)")
        val intent = Intent(this, AppLockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(AppLockActivity.EXTRA_PACKAGE, pkg)
            putExtra(AppLockActivity.EXTRA_APP_NAME, appName)
        }
        startActivity(intent)
    }

    override fun onInterrupt() {
        AppLogger.w(TAG, "AppLockService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        AppLogger.i(TAG, "AppLockService destroyed")
    }
}
