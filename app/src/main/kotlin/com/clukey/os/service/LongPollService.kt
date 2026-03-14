package com.clukey.os.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.IBinder
import android.speech.tts.TextToSpeech
import com.clukey.os.security.AppLockManager
import com.clukey.os.utils.AppLogger
import com.clukey.os.utils.PrefsManager
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class LongPollService : Service() {

    companion object {
        const val TAG = "LongPollService"
        private var lastEventId = ""
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ringtone: android.media.Ringtone? = null

    override fun onCreate() {
        super.onCreate()
        initTts()
        startPolling()
        AppLogger.i(TAG, "LongPollService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.US
        }
    }

    private fun startPolling() {
        scope.launch {
            while (isActive) {
                pollEvents()
                checkPhoneFinder()
                delay(PrefsManager.longPollIntervalMs.toLong())
            }
        }
    }

    private fun pollEvents() {
        try {
            val serverUrl = PrefsManager.serverUrl.ifBlank { return }
            val apiKey = PrefsManager.apiKey
            val url = java.net.URL("$serverUrl/pending_commands")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            if (apiKey.isNotBlank()) conn.setRequestProperty("X-CLUKEY-KEY", apiKey)
            conn.connectTimeout = 8000
            conn.readTimeout = 25000
            val responseCode = conn.responseCode
            if (responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                handlePendingCommands(JSONObject(body))
            } else {
                conn.disconnect()
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "Poll: ${e.message}")
        }
    }

    private fun handlePendingCommands(data: JSONObject) {
        val commands = data.optJSONArray("commands") ?: return
        for (i in 0 until commands.length()) {
            val cmd = commands.getJSONObject(i)
            if (cmd.optBoolean("executed")) continue
            val type    = cmd.optString("type", "")
            val payload = cmd.optString("payload", "")
            val cmdId   = cmd.optString("id", "")
            AppLogger.i(TAG, "Command: $type — $payload")
            when (type) {
                "speak"        -> speak(payload)
                "read_message" -> readLatestMessage()
                "ring_loudly"  -> ringLoudly()
                "ring_stop"    -> stopRinging()
                "timer_done"   -> { speak("$payload is done!"); playAlarmSound() }
                "focus_mode"   -> {
                    val mode = payload.ifBlank { "study" }
                    speak("$mode mode activated")
                    FocusModeManager.init(applicationContext)
                    FocusModeManager.startFocusMode(mode)
                }
                "lock_app"     -> {
                    AppLockManager.init(applicationContext)
                    AppLockManager.lockApp(payload)
                }
                "action"       -> executeAction(payload)
            }
            markCommandDone(cmdId)
        }
    }

    private fun executeAction(action: String) {
        scope.launch(Dispatchers.Main) {
            val executor = com.clukey.os.engine.CommandExecutor(applicationContext)
            executor.execute(action, "server_push")
        }
    }

    private fun speak(text: String) {
        if (!ttsReady || text.isBlank()) return
        scope.launch(Dispatchers.Main) {
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "lp_${System.currentTimeMillis()}")
        }
    }

    private fun readLatestMessage() {
        val latest = NotificationMonitorService.latestNotification.value
        if (latest != null) {
            speak("${latest.appName} from ${latest.sender}: ${latest.text.take(80)}")
        } else {
            speak("No recent messages.")
        }
    }

    private fun ringLoudly() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_ALARM,
                am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(this, uri)
            ringtone?.play()
            scope.launch { delay(30_000); stopRinging() }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Ring failed: ${e.message}")
        }
    }

    private fun stopRinging() {
        ringtone?.stop()
        ringtone = null
    }

    private fun playAlarmSound() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val r = RingtoneManager.getRingtone(this, uri)
