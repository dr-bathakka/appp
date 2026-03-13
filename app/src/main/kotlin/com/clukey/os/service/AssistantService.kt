package com.clukey.os.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.clukey.os.CluKeyApp
import com.clukey.os.R
import com.clukey.os.automation.AutomationEngine
import com.clukey.os.engine.CommandExecutor
import com.clukey.os.engine.OnDeviceAIEngine
import com.clukey.os.network.CloudSyncService
import com.clukey.os.network.HttpBridgeServer
import com.clukey.os.ui.MainActivity
import com.clukey.os.utils.AppLogger
import com.clukey.os.utils.PrefsManager
import kotlinx.coroutines.*

/**
 * AssistantService — the persistent foreground service that orchestrates:
 *   • CommandExecutor (local device control + TTS)
 *   • AutomationEngine (rule-based triggers)
 *   • OnDeviceAIEngine (local intent classification)
 *   • HttpBridgeServer (NanoHTTPD on port 8080)
 *   • CloudSyncService periodic device info push
 *
 * Started by MainActivity and BootReceiver.
 * Uses LifecycleService so coroutines tie to service lifecycle.
 */
class AssistantService : LifecycleService() {

    companion object {
        const val TAG = "AssistantService"
        const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.clukey.os.STOP_SERVICE"
        const val ACTION_COMMAND = "com.clukey.os.COMMAND"
        const val EXTRA_COMMAND = "command"
    }

    lateinit var executor: CommandExecutor
        private set
    lateinit var automationEngine: AutomationEngine
        private set
    lateinit var onDeviceAI: OnDeviceAIEngine
        private set

    private var httpServer: HttpBridgeServer? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        AppLogger.i(TAG, "AssistantService created")

        executor        = CommandExecutor(this)
        onDeviceAI      = OnDeviceAIEngine(this)
        automationEngine = AutomationEngine(this, executor)

        startForeground(NOTIF_ID, buildNotification())
        startHttpServer()
        automationEngine.start()
        startDeviceInfoPush()
        startNewServices()

        // Register device on first launch
        if (!PrefsManager.isRegistered) {
            scope.launch { CloudSyncService.registerDevice() }
        }

        // Retry any messages captured offline while server was unreachable
        scope.launch {
            delay(3000) // wait for network to settle first
            NotificationMonitorService.instance?.retrySyncUnsyncedMessages()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP    -> { stopSelf(); return START_NOT_STICKY }
            ACTION_COMMAND -> {
                val cmd = intent.getStringExtra(EXTRA_COMMAND) ?: return START_STICKY
                scope.launch { handleCommand(cmd) }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        AppLogger.i(TAG, "AssistantService destroyed")
        scope.cancel()
        httpServer?.stop()
        automationEngine.stop()
        executor.shutdown()
        super.onDestroy()
    }

    // ── Command handling ──────────────────────────────────────────────────────

    suspend fun handleCommand(text: String, source: String = "voice"): String {
        AppLogger.i(TAG, "[INPUT] $text  (source=$source)")

        // Check automation triggers first
        val wasAutomation = automationEngine.checkVoiceTrigger(text)
        if (wasAutomation) return "Automation triggered."

        // Classify intent on-device
        val classification = onDeviceAI.classifyIntent(text)
        AppLogger.i(TAG, "[INTENT] ${classification.intent} conf=${classification.confidence}")

        return when {
            classification.intent == OnDeviceAIEngine.Intent.COMMAND -> {
                val result = executor.execute(text, source)
                executor.speak(result.response)
                result.response
            }
            classification.confidence < 0.6f && !CloudSyncService.isConnected.value -> {
                val resp = onDeviceAI.offlineResponse(text)
                executor.speak(resp)
                resp
            }
            else -> {
                val result = CloudSyncService.sendChat(text, "android")
                val resp = result.getOrNull()?.response ?: onDeviceAI.offlineResponse(text)
                executor.speak(resp)
                resp
            }
        }
    }

    // ── New god-mode services ─────────────────────────────────────────────────

    private fun startNewServices() {
        // Device Health Monitor (battery, RAM → server every 30s)
        startService(Intent(this, DeviceHealthService::class.java))
        // GPS Location Tracker (→ server every 60s)
        startService(Intent(this, LocationService::class.java))
        // Intruder Detection (wrong unlock → front cam selfie)
        startService(Intent(this, IntruderService::class.java))
        // SMS & Call Log Sync (→ server every 5 mins)
        startService(Intent(this, SmsCallLogService::class.java))
        // App Usage Stats (→ server every hour)
        startService(Intent(this, AppUsageService::class.java))
        // ✅ NEW: Long-Poll Service (bypasses external phone lock notification issue)
        // Uses HTTP polling instead — works with ANY 3rd party lock screen
        startService(Intent(this, LongPollService::class.java))
        // ✅ NEW: Driving Mode (auto-activates at speed > 20 km/h)
        if (PrefsManager.drivingModeAutoEnabled) {
            startService(Intent(this, DrivingModeService::class.java))
        }
        // ✅ NEW: Init managers
        com.clukey.os.engine.ShoppingListManager.init(this)
        com.clukey.os.engine.VoiceNoteManager.init(this)
        com.clukey.os.service.FocusModeManager.init(this)
        com.clukey.os.security.AppLockManager.init(this)
        AppLogger.i(TAG, "All services started including v4 god mode extensions")
    }

    // ── HTTP server ───────────────────────────────────────────────────────────

    private fun startHttpServer() {
        if (!PrefsManager.httpServerEnabled) return
        try {
            httpServer = HttpBridgeServer(PrefsManager.httpServerPort, executor)
            httpServer!!.start()
            AppLogger.i(TAG, "HTTP bridge started on port ${PrefsManager.httpServerPort}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "HTTP server failed to start", e)
        }
    }

    // ── Periodic device info push ──────────────────────────────────────────────

    private fun startDeviceInfoPush() {
        scope.launch {
            while (isActive) {
                delay(60_000) // every 60 s
                try {
                    CloudSyncService.pushDeviceInfo()
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Device info push failed", e)
                }
            }
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, AssistantService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CluKeyApp.CHANNEL_ASSISTANT)
            .setSmallIcon(R.drawable.ic_clukey_notif)
            .setContentTitle("CluKey Assistant Running")
            .setContentText("AI brain connected • HTTP bridge active")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }
}
