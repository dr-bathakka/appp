package com.clukey.os.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.clukey.os.security.AppLockManager
import com.clukey.os.ui.AppLockActivity
import com.clukey.os.utils.AppLogger
import com.clukey.os.utils.PrefsManager

class AppLockService : AccessibilityService() {

    companion object {
        const val TAG = "AppLockService"
        var instance: AppLockService? = null
    }

    private var lastPackage = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Safety: init PrefsManager in case Application.onCreate() hasn't run yet
        try { PrefsManager.init(applicationContext) } catch (_: Exception) {}
        try { AppLogger.init(applicationContext) } catch (_: Exception) {}
        try { AppLockManager.init(applicationContext) } catch (_: Exception) {}

        instance = this

        serviceInfo = serviceInfo?.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
        }
        AppLogger.i(TAG, "AppLockService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        try {
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

            val intent = Intent(this, AppLockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(AppLockActivity.EXTRA_PACKAGE, pkg)
                putExtra(AppLockActivity.EXTRA_APP_NAME, appName)
            }
            startActivity(intent)
        } catch (_: Exception) {}
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
