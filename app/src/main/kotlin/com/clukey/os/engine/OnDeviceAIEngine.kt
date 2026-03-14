package com.clukey.os.engine

import android.content.Context
import com.clukey.os.utils.AppLogger

/**
 * OnDeviceAIEngine — lightweight on-device intent classifier.
 *
 * Uses rule-based pattern matching for common commands so the
 * assistant works offline without cloud round-trips.
 *
 * For heavier inference, swap classifyIntent() to use a TFLite model
 * loaded from assets/intent_classifier.tflite.
 */
class OnDeviceAIEngine(private val context: Context) {

    private val TAG = "OnDeviceAIEngine"

    enum class Intent {
        COMMAND, CHAT, MEMORY, PLANNING, UNKNOWN
    }

    data class Classification(
        val intent: Intent,
        val confidence: Float,
        val extractedCommand: String = ""
    )

    // ── Pattern tables ────────────────────────────────────────────────────────

    private val commandPatterns = listOf(
        Regex("""\b(open|close|launch)\b"""),
        Regex("""\b(lock|unlock)\b"""),
        Regex("""\b(wifi|bluetooth|hotspot|mobile\s?data)\b"""),
        Regex("""\b(volume|brightness|screenshot|flashlight|camera)\b"""),
        Regex("""\b(remind|reminder|note|event|calendar)\b"""),
        Regex("""\b(study|sleep|work)\s+mode\b"""),
    )

    private val chatOverrides = listOf(
        "joke", "how are you", "who are you", "tell me", "i'm bored", "i'm tired",
        "what should i", "entertain", "hello", "hey", "hi"
    )

    private val planKeywords = listOf(
        "how do i", "how should i", "steps to", "plan for", "help me plan"
    )

    private val memoryKeywords = listOf(
        "my name is", "remember that", "remember this", "my birthday", "my exam"
    )

    // ── Classifier ────────────────────────────────────────────────────────────

    fun classifyIntent(text: String): Classification {
        val tl = text.lowercase().trim()

        // Tier 1 — chat overrides (never route to command)
        chatOverrides.forEach { kw ->
            if (kw in tl) {
                AppLogger.d(TAG, "chat_override: $kw")
                return Classification(Intent.CHAT, 0.93f)
            }
        }

        // Tier 2 — command patterns
        commandPatterns.forEach { pattern ->
            if (pattern.containsMatchIn(tl)) {
                AppLogger.d(TAG, "command_match: ${pattern.pattern}")
                return Classification(Intent.COMMAND, 0.87f)
            }
        }

        // Tier 3 — memory keywords
        memoryKeywords.forEach { kw ->
            if (kw in tl) return Classification(Intent.MEMORY, 0.90f)
        }

        // Tier 4 — planning keywords
        planKeywords.forEach { kw ->
            if (kw in tl) return Classification(Intent.PLANNING, 0.85f)
        }

        // Default — send to cloud AI
        return Classification(Intent.UNKNOWN, 0.50f)
    }

    // ── Offline fallback responses ─────────────────────────────────────────────

    fun offlineResponse(text: String): String {
        val tl = text.lowercase()
        return when {
            "hello" in tl || "hi" in tl || "hey" in tl -> "Hey, what's up?"
            "how are you" in tl                         -> "Running smooth."
            "your name" in tl                           -> "I'm CluKey, your AI assistant."
            "joke" in tl                                -> "Why do programmers prefer dark mode? Light attracts bugs."
            "thank" in tl                               -> "Anytime."
            "time" in tl                                -> "Check your status bar."
            else                                        -> "I'm offline right now. Please connect to the CluKey Brain."
        }
    }
}
