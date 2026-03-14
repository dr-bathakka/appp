package com.clukey.os.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.*
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import com.clukey.os.utils.AppLogger
import kotlinx.coroutines.*
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * TimerService — voice-activated timers and stopwatch.
 *
 * Usage examples:
 *   "Set a 5 minute timer"
 *   "Set timer for 30 seconds"
 *   "Start stopwatch"
 *   "Stop stopwatch"
 *   "Cancel timer"
 *
 * On completion: plays alarm sound + speaks "Timer done!"
 * Multiple timers supported (by name).
 */
object TimerManager {

    data class TimerEntry(
        val id: String,
        val name: String,
        val durationMs: Long,
        val startTime: Long = System.currentTimeMillis(),
        var job: Job? = null
    ) {
        val remainingMs get() = maxOf(0, (startTime + durationMs) - System.currentTimeMillis())
        val isExpired get() = remainingMs <= 0
        fun formattedRemaining(): String {
            val secs = (remainingMs / 1000).toInt()
            return when {
                secs >= 3600 -> "${secs/3600}h ${(secs%3600)/60}m"
                secs >= 60   -> "${secs/60}m ${secs%60}s"
                else         -> "${secs}s"
            }
        }
    }

    private val timers = ConcurrentHashMap<String, TimerEntry>()
    private var stopwatchStart = 0L
    private var stopwatchRunning = false

    fun addTimer(id: String, name: String, durationMs: Long): TimerEntry {
        val entry = TimerEntry(id, name, durationMs)
        timers[id] = entry
        return entry
    }

    fun removeTimer(id: String): Boolean = timers.remove(id) != null

    fun getAll() = timers.values.toList()

    fun getStatus(): String {
        if (timers.isEmpty() && !stopwatchRunning) return "No active timers."
        val parts = mutableListOf<String>()
        timers.values.forEach { t ->
            parts.add("${t.name}: ${t.formattedRemaining()} left")
        }
        if (stopwatchRunning) {
            val elapsed = (System.currentTimeMillis() - stopwatchStart) / 1000
            val m = elapsed / 60; val s = elapsed % 60
            parts.add("Stopwatch: ${m}m ${s}s")
        }
        return parts.joinToString(", ")
    }

    fun startStopwatch(): String {
        stopwatchStart = System.currentTimeMillis()
        stopwatchRunning = true
        return "Stopwatch started."
    }

    fun stopStopwatch(): String {
        if (!stopwatchRunning) return "Stopwatch is not running."
        val elapsed = (System.currentTimeMillis() - stopwatchStart) / 1000
        stopwatchRunning = false
        val m = elapsed / 60; val s = elapsed % 60
        return "Stopwatch stopped at ${m}m ${s}s."
    }

    fun clearAll() { timers.clear(); stopwatchRunning = false }
}

class TimerService : Service() {

    companion object {
        const val TAG = "TimerService"
        const val CHANNEL_ID = "clukey_timer"
        const val NOTIF_ID = 2004

        const val ACTION_SET_TIMER = "SET_TIMER"
        const val ACTION_CANCEL = "CANCEL_TIMER"
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_TIMER_NAME = "timer_name"

        fun setTimer(ctx: Context, durationMs: Long, name: String = "Timer") {
            val intent = Intent(ctx, TimerService::class.java).apply {
                action = ACTION_SET_TIMER
                putExtra(EXTRA_DURATION_MS, durationMs)
                putExtra(EXTRA_TIMER_NAME, name)
            }
            ctx.startService(intent)
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        initTts()
        startForeground(NOTIF_ID, buildNotification("Ready"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SET_TIMER -> {
                val ms = intent.getLongExtra(EXTRA_DURATION_MS, 60_000L)
                val name = intent.getStringExtra(EXTRA_TIMER_NAME) ?: "Timer"
                startTimer(ms, name)
            }
            ACTION_CANCEL -> {
                TimerManager.clearAll()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            ttsReady = (status == TextToSpeech.SUCCESS)
            if (ttsReady) tts?.language = Locale.US
        }
    }

    private fun startTimer(durationMs: Long, name: String) {
        val id = System.currentTimeMillis().toString()
        val seconds = durationMs / 1000
        val label = when {
            seconds >= 3600 -> "${seconds/3600} hour"
            seconds >= 60   -> "${seconds/60} minute"
            else            -> "$seconds second"
        }

        AppLogger.i(TAG, "⏱ Starting $name: ${label}s")
        tts?.speak("$name set for $label.", TextToSpeech.QUEUE_ADD, null, "timer_set")

        val entry = TimerManager.addTimer(id, name, durationMs)
        updateNotification("$name — $label remaining")

        val job = scope.launch {
            delay(durationMs)
            onTimerComplete(id, name)
        }
        entry.job = job
    }

    private fun onTimerComplete(id: String, name: String) {
        TimerManager.removeTimer(id)
        AppLogger.i(TAG, "⏰ $name DONE!")

        // Play alarm
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(this, alarmUri)
            ringtone.play()
            Handler(Looper.getMainLooper()).postDelayed({ ringtone.stop() }, 3000)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Alarm sound failed: ${e.message}")
        }

        // Speak
        tts?.speak("$name is done!", TextToSpeech.QUEUE_FLUSH, null, "timer_done")

        if (TimerManager.getAll().isEmpty()) {
            Handler(Looper.getMainLooper()).postDelayed({ stopSelf() }, 5000)
        } else {
            updateNotification(TimerManager.getStatus())
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "CluKey Timers", NotificationManager.IMPORTANCE_HIGH)
            (getSystemService(NotificationManager::class.java))?.createNotificationChannel(ch)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("⏱ CluKey Timer")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        tts?.shutdown()
        scope.cancel()
    }
}
