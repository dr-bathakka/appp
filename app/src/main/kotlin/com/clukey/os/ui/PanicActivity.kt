package com.clukey.os.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.telephony.SmsManager
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.clukey.os.utils.AppLogger
import com.clukey.os.utils.PrefsManager
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class PanicActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF1a0000.toInt()
        setContentView(buildUI())
    }

    override fun onDestroy() { super.onDestroy(); scope.cancel() }

    private lateinit var tvLog: TextView
    private lateinit var etContact1: EditText
    private lateinit var etContact2: EditText

    private fun buildUI(): ScrollView {
        val ctx = this
        val scroll = ScrollView(ctx).apply { setBackgroundColor(0xFF0A0000.toInt()) }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(40), dp(16), dp(40))
        }

        // Header
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(16))
            addView(TextView(ctx).apply {
                text = "←"; textSize = 20f
                setTextColor(0xFFF87171.toInt())
                setPadding(0, 0, dp(14), 0)
                setOnClickListener { finish() }
            })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(ctx).apply {
                    text = "🚨 PANIC MODE"
                    textSize = 20f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(0xFFF87171.toInt())
                    letterSpacing = 0.2f
                })
                addView(TextView(ctx).apply {
                    text = "EMERGENCY ALERT SYSTEM"
                    textSize = 9f
                    setTextColor(0xFFEF4444.toInt())
                    letterSpacing = 0.3f
                })
            })
        })

        // Trusted contacts
        root.addView(label(ctx, "TRUSTED CONTACT 1"))
        etContact1 = input(ctx, loadContact(1), "+91 9999999999")
        root.addView(etContact1)

        root.addView(label(ctx, "TRUSTED CONTACT 2"))
        etContact2 = input(ctx, loadContact(2), "+91 8888888888")
        root.addView(etContact2)

        root.addView(btn(ctx, "💾 SAVE CONTACTS", 0xFF1E293B.toInt(), 0xFF94A3B8.toInt()) {
            saveContacts()
        })

        root.addView(android.view.View(ctx).apply {
            setBackgroundColor(0xFF2D0000.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).also {
                it.topMargin = dp(20); it.bottomMargin = dp(20)
            }
        })

        // Info
        root.addView(TextView(ctx).apply {
            text = "When activated, this will:\n• Send your GPS location via SMS to both contacts\n• Upload location to server\n• Log the panic event"
            textSize = 12f; setTextColor(0xFF94A3B8.toInt())
            setPadding(0, 0, 0, dp(16))
        })

        // Panic button
        root.addView(Button(ctx).apply {
            text = "🚨  SEND PANIC ALERT NOW"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setBackgroundColor(0xFFDC2626.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(24), dp(20), dp(24), dp(20))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).also {
                it.bottomMargin = dp(12)
            }
            setOnClickListener { triggerPanic() }
        })

        tvLog = TextView(ctx).apply {
            text = ""
            textSize = 11f
            setTextColor(0xFF94A3B8.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
        }
        root.addView(tvLog)

        scroll.addView(root)
        return scroll
    }

    private fun triggerPanic() {
        log("🚨 Panic triggered...")
        saveContacts()
        val contacts = listOf(loadContact(1), loadContact(2)).filter { it.isNotBlank() }
        if (contacts.isEmpty()) {
            log("❌ No contacts saved!")
            Toast.makeText(this, "Please add trusted contacts first", Toast.LENGTH_LONG).show()
            return
        }
        scope.launch {
            val location = getLocation()
            val locText = if (location != null)
                "Lat: ${location.first}, Lng: ${location.second}"
            else "Location unavailable"
            val mapsLink = if (location != null)
                "https://maps.google.com/?q=${location.first},${location.second}"
            else ""

            val smsText = "🚨 EMERGENCY ALERT from CluKey!\n" +
                "I need help! My location:\n$mapsLink\n$locText\n" +
                "Sent at ${java.text.SimpleDateFormat("HH:mm dd/MM", java.util.Locale.getDefault()).format(java.util.Date())}"

            // Send SMS
            if (ContextCompat.checkSelfPermission(this@PanicActivity,
                    Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                contacts.forEach { number ->
                    try {
                        @Suppress("DEPRECATION")
                        SmsManager.getDefault().sendTextMessage(number, null, smsText, null, null)
                        log("✅ SMS sent to $number")
                    } catch (e: Exception) {
                        log("❌ SMS failed to $number: ${e.message}")
                    }
                }
            } else {
                log("⚠️ SMS permission not granted")
            }

            // Push to server
            try {
                val http = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS).build()
                val bodyStr = JSONObject().apply {
                    put("reason", "manual_panic")
                    if (location != null) {
                        put("lat", location.first); put("lng", location.second)
                    }
                    put("contacts_alerted", contacts.size)
                }.toString()
                val body = bodyStr.toRequestBody("application/json".toMediaType())
                val req = Request.Builder()
                    .url("${PrefsManager.serverUrl.trimEnd('/')}/panic")
                    .addHeader("X-CLUKEY-KEY", PrefsManager.apiKey)
                    .post(body).build()
                http.newCall(req).execute()
                log("✅ Server notified")
            } catch (e: Exception) {
                log("⚠️ Server notify failed: ${e.message}")
            }

            log("✅ Panic alert complete")
        }
    }

    private fun getLocation(): Pair<Double, Double>? {
        return try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val loc = if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } else null
            loc?.let { Pair(it.latitude, it.longitude) }
        } catch (_: Exception) { null }
    }

    private fun toRequestBody2() = let {
        this.toString().toRequestBody("application/json".toMediaType())
    }

    private fun log(msg: String) {
        tvLog.append("\n$msg")
        AppLogger.i("Panic", msg)
    }

    private fun saveContacts() {
        val prefs = getSharedPreferences("clukey_panic", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("contact1", etContact1.text.toString().trim())
            .putString("contact2", etContact2.text.toString().trim())
            .apply()
    }

    private fun loadContact(n: Int): String {
        val prefs = getSharedPreferences("clukey_panic", Context.MODE_PRIVATE)
        return prefs.getString("contact$n", "") ?: ""
    }

    private fun label(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text; textSize = 10f
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(0xFFF87171.toInt()); letterSpacing = 0.2f
        setPadding(0, dp(14), 0, dp(6))
    }

    private fun input(ctx: Context, value: String, hint: String) = EditText(ctx).apply {
        setText(value); this.hint = hint
        textSize = 13f; setTextColor(0xFFE2E8F0.toInt())
        setHintTextColor(0xFF475569.toInt()); setBackgroundColor(0xFF111827.toInt())
        setPadding(dp(12), dp(10), dp(12), dp(10))
        inputType = android.text.InputType.TYPE_CLASS_PHONE
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(4) }
    }

    private fun btn(ctx: Context, text: String, bg: Int, fg: Int, action: () -> Unit) =
        Button(ctx).apply {
            this.text = text; textSize = 11f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setBackgroundColor(bg); setTextColor(fg)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(6) }
            setOnClickListener { action() }
        }

    private fun String.toRequestBody2() =
        this.toRequestBody("application/json".toMediaType())
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
