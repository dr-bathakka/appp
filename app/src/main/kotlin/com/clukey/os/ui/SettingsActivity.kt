package com.clukey.os.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.clukey.os.utils.PrefsManager

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PrefsManager.init(applicationContext)
        window.statusBarColor = 0xFF0A0E1A.toInt()
        setContentView(buildUI())
    }

    private fun buildUI(): ScrollView {
        val ctx = this
        val scroll = ScrollView(ctx).apply { setBackgroundColor(0xFF0A0E1A.toInt()) }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(40), dp(16), dp(40))
        }

        // Header
        root.addView(row(ctx,
            back(ctx) { finish() },
            header(ctx, "SETTINGS", "Configure CluKey")
        ))
        root.addView(divider(ctx))

        // ── SERVER ──
        root.addView(section(ctx, "🌐  SERVER CONNECTION"))
        val etUrl = pref(ctx, "Server URL", PrefsManager.serverUrl,
            "https://your-app.railway.app", false)
        root.addView(etUrl)
        val etKey = pref(ctx, "API Key", PrefsManager.apiKey,
            "your_secret_key", true)
        root.addView(etKey)
        root.addView(saveBtn(ctx, "Save Server Config") {
            PrefsManager.serverUrl = etUrl.tag as String
            PrefsManager.apiKey = etKey.tag as String
            toast("Server config saved ✅")
        })

        // ── VOICE ──
        root.addView(section(ctx, "🎤  VOICE"))
        val etWake = pref(ctx, "Wake Word", PrefsManager.wakeWord, "clukey", false)
        root.addView(etWake)
        root.addView(toggle(ctx, "Wake Word Enabled", PrefsManager.wakeWordEnabled) {
            PrefsManager.wakeWordEnabled = it
        })
        root.addView(saveBtn(ctx, "Save Voice Config") {
            PrefsManager.wakeWord = etWake.tag as String
            toast("Voice config saved ✅")
        })

        // ── NOTIFICATIONS ──
        root.addView(section(ctx, "🔔  NOTIFICATIONS"))
        root.addView(toggle(ctx, "Capture Notifications", PrefsManager.notificationsEnabled) {
            PrefsManager.notificationsEnabled = it
        })
        root.addView(toggle(ctx, "Capture ALL Apps (not just social)", PrefsManager.catchAllNotificationsEnabled) {
            PrefsManager.catchAllNotificationsEnabled = it
        })

        // ── LOCATION ──
        root.addView(section(ctx, "📍  LOCATION"))
        root.addView(toggle(ctx, "Location Tracking", PrefsManager.locationEnabled) {
            PrefsManager.locationEnabled = it
        })

        // ── APP LOCK ──
        root.addView(section(ctx, "🔒  APP LOCK"))
        val etPin = pref(ctx, "App Lock PIN", PrefsManager.appLockPin, "4-digit PIN", true)
        root.addView(etPin)
        root.addView(saveBtn(ctx, "Set PIN") {
            val pin = etPin.tag as String
            if (pin.length >= 4) {
                PrefsManager.appLockPin = pin
                toast("PIN set ✅")
            } else toast("PIN must be at least 4 digits")
        })

        // ── HTTP SERVER ──
        root.addView(section(ctx, "🌐  LOCAL HTTP SERVER"))
        root.addView(toggle(ctx, "HTTP Server (port 8080)", PrefsManager.httpServerEnabled) {
            PrefsManager.httpServerEnabled = it
        })

        // ── SYSTEM ──
        root.addView(section(ctx, "⚙️  SYSTEM"))
        root.addView(toggle(ctx, "Auto-Start on Boot", PrefsManager.autoStartEnabled) {
            PrefsManager.autoStartEnabled = it
        })
        root.addView(toggle(ctx, "Driving Mode Auto-Detect", PrefsManager.drivingModeAutoEnabled) {
            PrefsManager.drivingModeAutoEnabled = it
        })

        val etPoll = pref(ctx, "Sync Interval (ms)",
            PrefsManager.longPollIntervalMs.toString(), "5000", false)
        root.addView(etPoll)
        root.addView(saveBtn(ctx, "Save Sync Interval") {
            PrefsManager.longPollIntervalMs = (etPoll.tag as String).toLongOrNull() ?: 5000L
            toast("Sync interval saved ✅")
        })

        scroll.addView(root)
        return scroll
    }

    // ── WIDGET HELPERS ────────────────────────────────────────────────────────
    private fun pref(ctx: Context, label: String, value: String,
                     hint: String, secret: Boolean): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF111827.toInt())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            val p = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            p.bottomMargin = dp(4)
            layoutParams = p

            addView(TextView(ctx).apply {
                text = label
                textSize = 10f
                setTextColor(0xFF64748B.toInt())
                letterSpacing = 0.1f
                setPadding(0, 0, 0, dp(4))
            })
            val et = EditText(ctx).apply {
                setText(value)
                this.hint = hint
                textSize = 13f
                setTextColor(0xFFE2E8F0.toInt())
                setHintTextColor(0xFF475569.toInt())
                setBackgroundColor(0xFF1E293B.toInt())
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setSingleLine(true)
                if (secret) inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(s: android.text.Editable?) {
                        this@apply.parent?.let {
                            (it as LinearLayout).tag = s?.toString() ?: ""
                        }
                    }
                    override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                    override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                })
            }
            tag = value
            addView(et)
            et.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { tag = s?.toString() ?: "" }
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            })
        }
    }

    private fun toggle(ctx: Context, label: String, initial: Boolean,
                       onChange: (Boolean) -> Unit): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF111827.toInt())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            val p = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            p.bottomMargin = dp(4)
            layoutParams = p
            addView(TextView(ctx).apply {
                text = label
                textSize = 13f
                setTextColor(0xFFCBD5E1.toInt())
                val lp = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                layoutParams = lp
            })
            addView(Switch(ctx).apply {
                isChecked = initial
                setOnCheckedChangeListener { _, c -> onChange(c) }
            })
        }
    }

    private fun section(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        textSize = 10f
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(0xFF4FC3F7.toInt())
        letterSpacing = 0.3f
        setPadding(0, dp(20), 0, dp(6))
    }

    private fun saveBtn(ctx: Context, label: String, action: () -> Unit) = Button(ctx).apply {
        text = label
        textSize = 11f
        setTypeface(null, android.graphics.Typeface.BOLD)
        setBackgroundColor(0xFF065F46.toInt())
        setTextColor(0xFF34D399.toInt())
        setPadding(dp(16), dp(10), dp(16), dp(10))
        val p = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        p.topMargin = dp(6); p.bottomMargin = dp(6)
        layoutParams = p
        setOnClickListener { action() }
    }

    private fun header(ctx: Context, title: String, sub: String) =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(ctx).apply {
                text = title; textSize = 20f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(0xFFE2E8F0.toInt()); letterSpacing = 0.2f
            })
            addView(TextView(ctx).apply {
                text = sub; textSize = 9f
                setTextColor(0xFF4FC3F7.toInt()); letterSpacing = 0.3f
            })
        }

    private fun back(ctx: Context, action: () -> Unit) = TextView(ctx).apply {
        text = "←"; textSize = 20f
        setTextColor(0xFF4FC3F7.toInt())
        setPadding(0, 0, dp(14), 0)
        setOnClickListener { action() }
    }

    private fun row(ctx: Context, vararg views: android.view.View) =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
            views.forEach { addView(it) }
        }

    private fun divider(ctx: Context) = android.view.View(ctx).apply {
        setBackgroundColor(0xFF1E293B.toInt())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).also {
            it.bottomMargin = dp(8)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
