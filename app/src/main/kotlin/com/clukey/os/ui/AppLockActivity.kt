package com.clukey.os.ui

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.clukey.os.R
import com.clukey.os.security.AppLockManager
import com.clukey.os.utils.AppLogger

/**
 * AppLockActivity — shown when user opens a locked app.
 * Displays PIN entry. On correct PIN → session-unlocks the app.
 * On 3 wrong attempts → logs intruder.
 */
class AppLockActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PACKAGE  = "locked_package"
        const val EXTRA_APP_NAME = "locked_app_name"
        const val TAG = "AppLockActivity"
    }

    private var lockedPkg  = ""
    private var lockedName = ""
    private var attempts   = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lockedPkg  = intent.getStringExtra(EXTRA_PACKAGE)  ?: ""
        lockedName = intent.getStringExtra(EXTRA_APP_NAME) ?: lockedPkg

        AppLockManager.init(applicationContext)

        // Simple programmatic UI
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 128, 64, 64)
        }

        val title = TextView(this).apply {
            text = "🔒 $lockedName is locked"
            textSize = 20f
            setPadding(0, 0, 0, 32)
        }

        val pinInput = EditText(this).apply {
            hint = "Enter PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }

        val unlockBtn = Button(this).apply { text = "Unlock" }
        val cancelBtn = Button(this).apply { text = "Cancel" }

        unlockBtn.setOnClickListener {
            val pin = pinInput.text.toString()
            if (AppLockManager.verifyPin(pin)) {
                AppLockManager.sessionUnlock(lockedPkg)
                AppLogger.i(TAG, "App unlocked: $lockedName")
                finish()
            } else {
                attempts++
                pinInput.error = "Wrong PIN (attempt $attempts)"
                pinInput.text.clear()
                if (attempts >= 3) {
                    AppLockManager.logIntruder(reason = "wrong_pin_$attempts")
                    Toast.makeText(this, "Too many attempts. Intruder logged.", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }

        cancelBtn.setOnClickListener { finish() }

        layout.addView(title)
        layout.addView(pinInput)
        layout.addView(unlockBtn)
        layout.addView(cancelBtn)
        setContentView(layout)
    }

    override fun onBackPressed() {
        // Don't allow back-press to bypass the lock
        moveTaskToBack(true)
    }
}
