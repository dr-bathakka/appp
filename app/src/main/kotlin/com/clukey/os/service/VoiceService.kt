package com.clukey.os.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.clukey.os.utils.AppLogger
import com.clukey.os.utils.PrefsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * VoiceService — manages Android SpeechRecognizer for wake-word + command recognition.
 *
 * Wake phrases: "clukey", "hey clukey", "hi clukey" (configurable in PrefsManager)
 */
class VoiceService(
    private val context: Context,
    private val onCommand: suspend (String) -> Unit,
    private val onWakeDetected: () -> Unit
) {

    private val TAG = "VoiceService"
    private val scope = CoroutineScope(Dispatchers.Main)

    enum class State { IDLE, LISTENING_WAKE, LISTENING_COMMAND, PROCESSING }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    private var recognizer: SpeechRecognizer? = null
    private var commandMode = false
    private var active = false

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            AppLogger.e(TAG, "SpeechRecognizer not available on this device")
            return
        }
        active = true
        AppLogger.i(TAG, "VoiceService started — wake word: '${PrefsManager.wakeWord}'")
        listen()
    }

    fun stop() {
        active = false
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        _state.value = State.IDLE
        AppLogger.i(TAG, "VoiceService stopped")
    }

    @Volatile private var _listening = false

    private fun listen() {
        if (!active) return
        // ✅ FIX: prevent two recognizers being created simultaneously
        if (_listening) return
        _listening = true

        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer?.setRecognitionListener(object : RecognitionListener {

            override fun onReadyForSpeech(params: Bundle?) {
                _state.value = if (commandMode) State.LISTENING_COMMAND else State.LISTENING_WAKE
                AppLogger.d(TAG, "Listening (command=$commandMode)")
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: run { restartListen(); return }

                AppLogger.i(TAG, "[RECOGNIZED] $text (commandMode=$commandMode)")

                if (commandMode) {
                    commandMode = false
                    _state.value = State.PROCESSING
                    scope.launch {
                        try { onCommand(text) }
                        catch (e: Exception) { AppLogger.e(TAG, "onCommand error", e) }
                        finally { restartListen() }
                    }
                } else {
                    if (detectWake(text)) {
                        AppLogger.i(TAG, "[WAKE] Detected in: '$text'")
                        onWakeDetected()

                        val inline = extractInlineCommand(text)
                        if (inline.isNotBlank()) {
                            _state.value = State.PROCESSING
                            scope.launch {
                                try { onCommand(inline) }
                                finally { restartListen() }
                            }
                        } else {
                            commandMode = true
                            listen()
                        }
                    } else {
                        restartListen()
                    }
                }
            }

            override fun onError(error: Int) {
                val desc = errorDescription(error)
                AppLogger.d(TAG, "SpeechRecognizer error: $desc")
                // Recognizer busy needs a longer cooldown to avoid crash loop
                val delayMs = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1500L else 300L
                Handler(Looper.getMainLooper()).postDelayed({ listen() }, delayMs)
            }

            override fun onEndOfSpeech() {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onPartialResults(partial: Bundle?) {}
            override fun onEvent(type: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        recognizer?.startListening(intent)
    }

    private fun restartListen() {
        if (!active) return
        _listening = false  // ✅ allow next listen() call
        Handler(Looper.getMainLooper()).postDelayed({ listen() }, 300)
    }

    private val wakeVariants = listOf(
        "clukey", "clue key", "klukey", "clukie", "key", "lucky"
    )

    private fun detectWake(text: String): Boolean {
        val tl = text.lowercase()
        val configured = PrefsManager.wakeWord.lowercase()
        if (configured in tl) return true
        return wakeVariants.any { it in tl }
    }

    private fun extractInlineCommand(text: String): String {
        val tl = text.lowercase()
        val allWake = wakeVariants + listOf(PrefsManager.wakeWord, "hey", "hi", "ok", "okay")
        var result = tl
        allWake.forEach { result = result.replace(it, "").trim() }
        return result.trim()
    }

    private fun errorDescription(code: Int) = when (code) {
        SpeechRecognizer.ERROR_AUDIO               -> "audio error"
        SpeechRecognizer.ERROR_CLIENT              -> "client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permission denied"
        SpeechRecognizer.ERROR_NETWORK             -> "network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT     -> "network timeout"
        SpeechRecognizer.ERROR_NO_MATCH            -> "no match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY     -> "recognizer busy"
        SpeechRecognizer.ERROR_SERVER              -> "server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT      -> "speech timeout"
        else                                       -> "unknown ($code)"
    }
}
