package com.clukey.os.automation

import android.content.Context
import com.clukey.os.engine.CommandExecutor
import com.clukey.os.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * AutomationEngine — rule-based automation triggers.
 *
 * Trigger types:
 *   TIME       — fires at a specific HH:MM
 *   VOICE      — fires when a keyword is spoken
 *   NOTIFICATION — fires on package + keyword match
 *
 * Rules are evaluated by the engine loop (every 30 s for time triggers).
 * Voice and notification triggers are injected externally via checkVoiceTrigger()
 * and checkNotificationTrigger().
 */
class AutomationEngine(
    private val context: Context,
    private val executor: CommandExecutor
) {
    private val TAG = "AutomationEngine"
    private val scope = CoroutineScope(Dispatchers.Default)

    data class AutomationRule(
        val id: String,
        val name: String,
        val triggerType: TriggerType,
        val triggerValue: String,   // "07:00" / keyword / package name
        val action: String,         // command to execute
        var enabled: Boolean = true,
        var lastFired: Long = 0L
    )

    enum class TriggerType { TIME, VOICE, NOTIFICATION }

    private val rules = mutableListOf<AutomationRule>().apply {
        // ── Default rules ──────────────────────────────────────────────────
        add(AutomationRule("1", "Good morning", TriggerType.TIME,    "07:00", "speak Good morning! Ready for the day?"))
        add(AutomationRule("2", "Good night",   TriggerType.VOICE,   "good night", "sleep mode"))
        add(AutomationRule("3", "Study time",   TriggerType.VOICE,   "start studying", "study mode"))
        add(AutomationRule("4", "Work mode",    TriggerType.VOICE,   "work mode", "work mode"))
        add(AutomationRule("5", "WA read",      TriggerType.NOTIFICATION, "com.whatsapp", "read notification"))
    }

    private var running = false

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun start() {
        running = true
        AppLogger.i(TAG, "Automation engine started (${rules.size} rules)")
        scope.launch { timeLoop() }
    }

    fun stop() {
        running = false
    }

    // ── Time loop (checks every 30 s) ─────────────────────────────────────────

    private suspend fun timeLoop() {
        while (running) {
            checkTimeTriggers()
            delay(30_000)
        }
    }

    private suspend fun checkTimeTriggers() {
        val cal = Calendar.getInstance()
        val now = String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        val nowMs = System.currentTimeMillis()

        rules.filter { it.enabled && it.triggerType == TriggerType.TIME && it.triggerValue == now }
            .filter { nowMs - it.lastFired > 60_000 }   // prevent double-fire within 1 min
            .forEach { rule ->
                AppLogger.i(TAG, "[TIME] Firing rule '${rule.name}'")
                rule.lastFired = nowMs
                executor.execute(rule.action, "automation")
            }
    }

    // ── Voice trigger (called by VoiceService) ────────────────────────────────

    suspend fun checkVoiceTrigger(text: String): Boolean {
        val tl = text.lowercase()
        val matches = rules.filter {
            it.enabled && it.triggerType == TriggerType.VOICE && tl.contains(it.triggerValue)
        }
        matches.forEach { rule ->
            AppLogger.i(TAG, "[VOICE] Firing rule '${rule.name}'")
            executor.execute(rule.action, "automation_voice")
        }
        return matches.isNotEmpty()
    }

    // ── Notification trigger (called by NotificationMonitorService) ───────────

    suspend fun checkNotificationTrigger(packageName: String, text: String) {
        rules.filter {
            it.enabled && it.triggerType == TriggerType.NOTIFICATION
                && packageName.contains(it.triggerValue)
        }.forEach { rule ->
            AppLogger.i(TAG, "[NOTIF] Firing rule '${rule.name}'")
            if (rule.action == "read notification") {
                executor.speak("Message from ${friendlyName(packageName)}: $text")
            } else {
                executor.execute(rule.action, "automation_notification")
            }
        }
    }

    // ── Rule management ───────────────────────────────────────────────────────

    fun addRule(rule: AutomationRule) {
        rules.add(rule)
        AppLogger.i(TAG, "Rule added: ${rule.name}")
    }

    fun getRules(): List<AutomationRule> = rules.toList()

    fun setRuleEnabled(id: String, enabled: Boolean) {
        rules.find { it.id == id }?.enabled = enabled
    }

    private fun friendlyName(pkg: String) = when {
        pkg.contains("whatsapp")   -> "WhatsApp"
        pkg.contains("instagram")  -> "Instagram"
        pkg.contains("telegram")   -> "Telegram"
        pkg.contains("gmail")      -> "Gmail"
        else                        -> pkg.substringAfterLast('.')
    }
}
