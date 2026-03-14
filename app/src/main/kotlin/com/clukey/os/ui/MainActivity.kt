package com.clukey.os.ui

import android.Manifest
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.clukey.os.service.AssistantService
import com.clukey.os.utils.AppLogger
import com.clukey.os.utils.PrefsManager

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQ_PERMISSIONS = 100
    }

    private val dangerousPerms = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_SMS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.READ_PHONE_STATE,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PrefsManager.init(applicationContext)
        AppLogger.init(applicationContext)
        setContentView(buildUI())
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
    }

    // ── Build entire UI programmatically ──────────────────────────────────────

    private lateinit var scrollRoot: ScrollView
    private lateinit var permGrid: LinearLayout
    private lateinit var tvServerUrl: EditText
    private lateinit var tvApiKey: EditText
    private lateinit var tvStatus: TextView
    private lateinit var btnStart: Button

    private fun buildUI(): ScrollView {
        val ctx = this

        scrollRoot = ScrollView(ctx).apply {
            setBackgroundColor(0xFF0A0E1A.toInt())
        }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(40), dp(20), dp(40))
        }

        // ── HEADER ──
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))

            addView(TextView(ctx).apply {
                text = "⬡"
                textSize = 28f
                setTextColor(0xFF4FC3F7.toInt())
                setPadding(0, 0, dp(10), 0)
            })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(ctx).apply {
                    text = "CLUKEY"
                    textSize = 22f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(0xFFE2E8F0.toInt())
                    letterSpacing = 0.2f
                })
                addView(TextView(ctx).apply {
                    text = "AI SYSTEM v5.0"
                    textSize = 10f
                    setTextColor(0xFF4FC3F7.toInt())
                    letterSpacing = 0.3f
                })
            })
        })

        root.addView(divider(ctx))

        // ── STATUS BAR ──
        tvStatus = TextView(ctx).apply {
            text = "● INITIALIZING..."
            textSize = 12f
            setTextColor(0xFFFBBF24.toInt())
            letterSpacing = 0.1f
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(tvStatus)

        // ── SERVICE STATUS CARDS ──
        root.addView(sectionLabel(ctx, "SYSTEM STATUS"))
        root.addView(buildStatusCards(ctx))
        root.addView(spacer(ctx))

        // ── PERMISSIONS ──
        root.addView(sectionLabel(ctx, "PERMISSIONS"))
        permGrid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(permGrid)
        root.addView(Button(ctx).apply {
            text = "REQUEST ALL PERMISSIONS"
            textSize = 11f
            setTypeface(null, android.graphics.Typeface.BOLD)
            letterSpacing = 0.15f
            setBackgroundColor(0xFF1E3A5F.toInt())
            setTextColor(0xFF4FC3F7.toInt())
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { requestAllPermissions() }
        })
        root.addView(spacer(ctx))

        // ── SERVER CONFIG ──
        root.addView(sectionLabel(ctx, "SERVER CONFIG"))
        root.addView(configCard(ctx))
        root.addView(spacer(ctx))

        // ── SPECIAL PERMISSIONS ──
        root.addView(sectionLabel(ctx, "SPECIAL ACCESS"))
        root.addView(buildSpecialPerms(ctx))
        root.addView(spacer(ctx))

        // ── START / STOP ──
        btnStart = Button(ctx).apply {
            text = "▶  START CLUKEY"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            letterSpacing = 0.2f
            setBackgroundColor(0xFF0D6EFD.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setOnClickListener { toggleService() }
        }
        root.addView(btnStart)

        // Navigation grid - all screens
        root.addView(TextView(ctx).apply {
            text = "SCREENS"
            textSize = 10f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF4FC3F7.toInt())
            letterSpacing = 0.3f
            setPadding(0, dp(16), 0, dp(8))
        })

        val navGrid = android.widget.GridLayout(ctx).apply {
            columnCount = 2
            rowCount = 3
            useDefaultMargins = true
        }

        fun navBtn(icon: String, label: String, color: Int, action: () -> Unit): LinearLayout {
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setBackgroundColor(0xFF111827.toInt())
                setPadding(dp(8), dp(14), dp(8), dp(14))
                val p = android.widget.GridLayout.LayoutParams().apply {
                    width = 0; columnSpec = android.widget.GridLayout.spec(
                        android.widget.GridLayout.UNDEFINED, 1f)
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                }
                layoutParams = p
                addView(TextView(ctx).apply {
                    text = icon; textSize = 24f
                    gravity = android.view.Gravity.CENTER
                })
                addView(TextView(ctx).apply {
                    text = label; textSize = 10f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(color); gravity = android.view.Gravity.CENTER
                    letterSpacing = 0.1f; setPadding(0, dp(4), 0, 0)
                })
                setOnClickListener { action() }
            }
        }

        navGrid.addView(navBtn("⇄", "SHARE", 0xFF4FC3F7.toInt()) {
            startActivity(android.content.Intent(ctx, ShareActivity::class.java))
        })
        navGrid.addView(navBtn("⚙️", "SETTINGS", 0xFF94A3B8.toInt()) {
            startActivity(android.content.Intent(ctx, SettingsActivity::class.java))
        })
        navGrid.addView(navBtn("📊", "SCREEN TIME", 0xFF4ADE80.toInt()) {
            startActivity(android.content.Intent(ctx, AppUsageActivity::class.java))
        })
        navGrid.addView(navBtn("🚨", "PANIC", 0xFFF87171.toInt()) {
            startActivity(android.content.Intent(ctx, PanicActivity::class.java))
        })
        root.addView(navGrid)

        // Share button
        root.addView(Button(ctx).apply {
            text = "⇄  SHARE  —  PHONE ↔ PC"
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            letterSpacing = 0.1f
            setBackgroundColor(0xFF0F2A44.toInt())
            setTextColor(0xFF4FC3F7.toInt())
            setPadding(dp(20), dp(14), dp(20), dp(14))
            val p = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            p.topMargin = dp(10)
            layoutParams = p
            setOnClickListener {
                startActivity(android.content.Intent(ctx, ShareActivity::class.java))
            }
        })

        root.addView(TextView(ctx).apply {
            text = "CluKey runs as a background service.\nControl it from your server dashboard."
            textSize = 11f
            setTextColor(0xFF475569.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        })

        scrollRoot.addView(root)
        return scrollRoot
    }

    // ── STATUS CARDS ─────────────────────────────────────────────────────────

    private lateinit var cardAssistant: TextView
    private lateinit var cardServer: TextView
    private lateinit var cardBattery: TextView
    private lateinit var cardMode: TextView

    private fun buildStatusCards(ctx: Context): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 4f

            fun card(label: String, valueRef: (TextView) -> Unit): LinearLayout {
                return LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(0xFF111827.toInt())
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    params.setMargins(dp(2), 0, dp(2), 0)
                    layoutParams = params

                    val tv = TextView(ctx).apply {
                        text = "--"
                        textSize = 16f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(0xFF4FC3F7.toInt())
                    }
                    valueRef(tv)
                    addView(tv)
                    addView(TextView(ctx).apply {
                        text = label
                        textSize = 9f
                        setTextColor(0xFF64748B.toInt())
                        letterSpacing = 0.1f
                    })
                }
            }

            addView(card("SERVICE") { cardAssistant = it })
            addView(card("SERVER") { cardServer = it })
            addView(card("BATTERY") { cardBattery = it })
            addView(card("MODE") { cardMode = it })
        }
    }

    // ── CONFIG CARD ───────────────────────────────────────────────────────────

    private fun configCard(ctx: Context): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF111827.toInt())
            setPadding(dp(16), dp(16), dp(16), dp(16))

            addView(TextView(ctx).apply {
                text = "Server URL"
                textSize = 10f
                setTextColor(0xFF64748B.toInt())
                letterSpacing = 0.1f
                setPadding(0, 0, 0, dp(4))
            })
            tvServerUrl = EditText(ctx).apply {
                setText(PrefsManager.serverUrl)
                textSize = 13f
                setTextColor(0xFFE2E8F0.toInt())
                setHintTextColor(0xFF475569.toInt())
                hint = "https://your-server.railway.app"
                setBackgroundColor(0xFF1E293B.toInt())
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setSingleLine(true)
            }
            addView(tvServerUrl)

            addView(TextView(ctx).apply {
                text = "API Key"
                textSize = 10f
                setTextColor(0xFF64748B.toInt())
                letterSpacing = 0.1f
                setPadding(0, dp(12), 0, dp(4))
            })
            tvApiKey = EditText(ctx).apply {
                setText(PrefsManager.apiKey)
                textSize = 13f
                setTextColor(0xFFE2E8F0.toInt())
                setHintTextColor(0xFF475569.toInt())
                hint = "your_api_key"
                setBackgroundColor(0xFF1E293B.toInt())
                setPadding(dp(12), dp(10), dp(12), dp(10))
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            addView(tvApiKey)

            addView(Button(ctx).apply {
                text = "SAVE CONFIG"
                textSize = 11f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setBackgroundColor(0xFF065F46.toInt())
                setTextColor(0xFF34D399.toInt())
                setPadding(dp(16), dp(10), dp(16), dp(10))
                val p = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                p.topMargin = dp(12)
                layoutParams = p
                setOnClickListener { saveConfig() }
            })
        }
    }

    // ── SPECIAL PERMISSIONS ───────────────────────────────────────────────────

    private fun buildSpecialPerms(ctx: Context): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL

            fun specialRow(icon: String, label: String, desc: String, action: () -> Unit): LinearLayout {
                return LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setBackgroundColor(0xFF111827.toInt())
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    val p = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    p.bottomMargin = dp(4)
                    layoutParams = p

                    addView(TextView(ctx).apply {
                        text = icon
                        textSize = 18f
                        setPadding(0, 0, dp(12), 0)
                    })
                    addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        layoutParams = lp
                        addView(TextView(ctx).apply {
                            text = label
                            textSize = 12f
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            setTextColor(0xFFCBD5E1.toInt())
                        })
                        addView(TextView(ctx).apply {
                            text = desc
                            textSize = 10f
                            setTextColor(0xFF64748B.toInt())
                        })
                    })
                    addView(Button(ctx).apply {
                        text = "GRANT →"
                        textSize = 9f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setBackgroundColor(0xFF1E293B.toInt())
                        setTextColor(0xFF4FC3F7.toInt())
                        setPadding(dp(10), dp(6), dp(10), dp(6))
                        setOnClickListener { action() }
                    })
                }
            }

            addView(specialRow("♿", "Accessibility Service",
                "App lock & screen control") { openAccessibilitySettings() })
            addView(specialRow("🔔", "Notification Access",
                "Read & capture notifications") { openNotificationSettings() })
            addView(specialRow("🪟", "Display Over Apps",
                "Floating AI overlay bubble") { openOverlaySettings() })
            addView(specialRow("📊", "Usage Access",
                "App usage & screen time") { openUsageSettings() })
            addView(specialRow("🔋", "Battery Optimization",
                "Keep alive in background") { openBatterySettings() })
        }
    }

    // ── PERMISSION STATUS ROWS ────────────────────────────────────────────────

    private fun refreshPermissionStatus() {
        permGrid.removeAllViews()

        val ctx = this
        val permsToCheck = listOf(
            Triple("📷 Camera", Manifest.permission.CAMERA, "intruder photos"),
            Triple("🎤 Microphone", Manifest.permission.RECORD_AUDIO, "voice commands"),
            Triple("📍 Location", Manifest.permission.ACCESS_FINE_LOCATION, "tracking"),
            Triple("💬 SMS", Manifest.permission.READ_SMS, "message capture"),
            Triple("📞 Phone", Manifest.permission.CALL_PHONE, "call control"),
            Triple("📒 Contacts", Manifest.permission.READ_CONTACTS, "contact sync"),
        )

        for ((label, perm, use) in permsToCheck) {
            val granted = ContextCompat.checkSelfPermission(ctx, perm) ==
                          PackageManager.PERMISSION_GRANTED
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setBackgroundColor(0xFF111827.toInt())
                val p = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                p.bottomMargin = dp(3)
                layoutParams = p
            }
            row.addView(TextView(ctx).apply {
                text = label
                textSize = 12f
                setTextColor(0xFFCBD5E1.toInt())
                val lp = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                layoutParams = lp
            })
            row.addView(TextView(ctx).apply {
                text = use
                textSize = 10f
                setTextColor(0xFF475569.toInt())
                val lp = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                layoutParams = lp
            })
            row.addView(TextView(ctx).apply {
                text = if (granted) "✅ OK" else "❌ DENY"
                textSize = 11f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(if (granted) 0xFF4ADE80.toInt() else 0xFFF87171.toInt())
            })
            permGrid.addView(row)
        }

        // Special permissions
        val accessOk = isAccessibilityEnabled()
        val notifOk  = isNotificationListenerEnabled()
        val overlayOk = Settings.canDrawOverlays(this)

        updateSpecialStatus()
        updateServiceStatus()
    }

    private fun updateSpecialStatus() {
        val accessOk = isAccessibilityEnabled()
        val notifOk  = isNotificationListenerEnabled()
        val overlayOk = Settings.canDrawOverlays(this)

        val allOk = accessOk && notifOk && overlayOk
        tvStatus.text = if (allOk) "● ALL SYSTEMS READY" else "● PERMISSIONS NEEDED"
        tvStatus.setTextColor(if (allOk) 0xFF4ADE80.toInt() else 0xFFFBBF24.toInt())
    }

    private fun updateServiceStatus() {
        val running = isServiceRunning(AssistantService::class.java)
        if (::cardAssistant.isInitialized) {
            cardAssistant.text = if (running) "ON" else "OFF"
            cardAssistant.setTextColor(
                if (running) 0xFF4ADE80.toInt() else 0xFFF87171.toInt()
            )
        }
        if (::btnStart.isInitialized) {
            btnStart.text = if (running) "■  STOP CLUKEY" else "▶  START CLUKEY"
            btnStart.setBackgroundColor(
                if (running) 0xFF7F1D1D.toInt() else 0xFF0D6EFD.toInt()
            )
        }
        if (::cardServer.isInitialized) {
            val url = PrefsManager.serverUrl
            cardServer.text = if (url.isNotBlank() && url != "http://10.0.2.2:5000") "SET" else "NOT SET"
            cardServer.setTextColor(
                if (url.isNotBlank() && url != "http://10.0.2.2:5000") 0xFF4ADE80.toInt() else 0xFFFBBF24.toInt()
            )
        }
        if (::cardMode.isInitialized) {
            cardMode.text = PrefsManager.focusMode.uppercase()
            cardMode.setTextColor(0xFF4FC3F7.toInt())
        }

        // Battery
        try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            val bat = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (::cardBattery.isInitialized) {
                cardBattery.text = "$bat%"
                cardBattery.setTextColor(
                    when {
                        bat < 20 -> 0xFFF87171.toInt()
                        bat < 50 -> 0xFFFBBF24.toInt()
                        else -> 0xFF4ADE80.toInt()
                    }
                )
            }
        } catch (_: Exception) {}
    }

    // ── ACTIONS ───────────────────────────────────────────────────────────────

    private fun saveConfig() {
        val url = tvServerUrl.text.toString().trim()
        val key = tvApiKey.text.toString().trim()
        if (url.isNotBlank()) PrefsManager.serverUrl = url
        if (key.isNotBlank()) PrefsManager.apiKey = key
        Toast.makeText(this, "✅ Config saved", Toast.LENGTH_SHORT).show()
        updateServiceStatus()
    }

    private fun toggleService() {
        val running = isServiceRunning(AssistantService::class.java)
        if (running) {
            stopService(Intent(this, AssistantService::class.java))
            Toast.makeText(this, "CluKey stopped", Toast.LENGTH_SHORT).show()
        } else {
            try {
                startForegroundService(Intent(this, AssistantService::class.java))
                Toast.makeText(this, "CluKey started ✅", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Start failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        android.os.Handler(mainLooper).postDelayed({ updateServiceStatus() }, 800)
    }

    private fun requestAllPermissions() {
        val missing = dangerousPerms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            Toast.makeText(this, "All runtime permissions granted ✅", Toast.LENGTH_SHORT).show()
        } else {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>,
                                            grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS) refreshPermissionStatus()
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openNotificationSettings() {
        startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
    }

    private fun openOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")))
    }

    private fun openUsageSettings() {
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    private fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // ── HELPERS ───────────────────────────────────────────────────────────────

    private fun isServiceRunning(cls: Class<*>): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        return am.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == cls.name }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return enabled.contains(packageName, ignoreCase = true)
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners") ?: ""
        return enabled.contains(packageName, ignoreCase = true)
    }

    // ── UI HELPERS ────────────────────────────────────────────────────────────

    private fun sectionLabel(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        textSize = 10f
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(0xFF4FC3F7.toInt())
        letterSpacing = 0.3f
        setPadding(0, dp(16), 0, dp(6))
    }

    private fun divider(ctx: Context) = android.view.View(ctx).apply {
        setBackgroundColor(0xFF1E293B.toInt())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).also {
            it.topMargin = dp(8); it.bottomMargin = dp(8)
        }
    }

    private fun spacer(ctx: Context) = android.view.View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(8))
    }

    private fun dp(value: Int) =
        (value * resources.displayMetrics.density).toInt()
}
