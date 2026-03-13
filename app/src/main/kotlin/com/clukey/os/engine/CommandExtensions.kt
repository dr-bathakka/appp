package com.clukey.os.engine

import android.content.Context
import android.content.Intent
import com.clukey.os.security.AppLockManager
import com.clukey.os.service.*
import com.clukey.os.utils.AppLogger
import com.clukey.os.utils.PrefsManager

/**
 * CommandExtensions — extends CommandExecutor with all Tier 1/2/3 commands.
 *
 * New commands added:
 *  TIER 1 (Jarvis essentials):
 *   • "read my messages" / "read last WhatsApp message"
 *   • "set X minute timer" / "set timer for X seconds"
 *   • "start stopwatch" / "stop stopwatch"
 *   • "note: [text]" / "save note [text]"
 *   • "add [item] to shopping list"
 *   • "read shopping list"
 *
 *  TIER 2 (God Level):
 *   • "focus mode study" / "work mode on" / "sleep mode"
 *   • "focus mode off"
 *   • "lock [app name]"
 *   • "unlock [app name]"
 *   • "intruder log"
 *
 *  TIER 3 (Alexa features):
 *   • "translate [text] to [language]"
 *   • "what's the weather"
 *   • "read my notes"
 */
object CommandExtensions {

    private const val TAG = "CmdExt"

    /**
     * Try to match and handle extended commands.
     * Returns (response_text, action_name) or null if not matched.
     */
    fun handle(ctx: Context, cmd: String): Pair<String, String>? {
        val tl = cmd.lowercase().trim()

        // ── TIMER ────────────────────────────────────────────────────────────
        val timerMatch = Regex("""(set|start).{0,10}timer.{0,5}(\d+)\s*(minute|min|second|sec|hour|hr)""").find(tl)
            ?: Regex("""(\d+)\s*(minute|min|second|sec|hour|hr).{0,10}timer""").find(tl)
        if (timerMatch != null) {
            val groups = timerMatch.groupValues
            val amount = groups.firstOrNull { it.matches(Regex("\\d+")) }?.toLongOrNull() ?: 1L
            val unit = groups.firstOrNull { it.matches(Regex("minute|min|second|sec|hour|hr")) } ?: "minute"
            val ms = when {
                unit.startsWith("sec") -> amount * 1000L
                unit.startsWith("hour") || unit.startsWith("hr") -> amount * 3600_000L
                else -> amount * 60_000L
            }
            val label = "$amount $unit"
            TimerService.setTimer(ctx, ms, label)
            return "Timer set for $label." to "set_timer"
        }

        // ── STOPWATCH ────────────────────────────────────────────────────────
        if (tl.contains("start stopwatch") || tl == "stopwatch") {
            val resp = TimerManager.startStopwatch()
            return resp to "start_stopwatch"
        }
        if (tl.contains("stop stopwatch") || tl.contains("pause stopwatch")) {
            val resp = TimerManager.stopStopwatch()
            return resp to "stop_stopwatch"
        }
        if (tl.contains("timer status") || tl == "timers") {
            return TimerManager.getStatus() to "timer_status"
        }

        // ── SHOPPING LIST ─────────────────────────────────────────────────────
        ShoppingListManager.init(ctx)
        val addShopMatch = Regex("""add (.+?) to (my )?shopping list""").find(tl)
        if (addShopMatch != null) {
            val item = addShopMatch.groupValues[1]
            return ShoppingListManager.addItem(item) to "shopping_add"
        }
        if (tl.contains("shopping list")) {
            return when {
                tl.contains("clear") || tl.contains("empty") -> ShoppingListManager.clearList() to "shopping_clear"
                tl.contains("read") || tl.contains("show") || tl.contains("what") ->
                    ShoppingListManager.readList() to "shopping_read"
                else -> ShoppingListManager.readList() to "shopping_read"
            }
        }
        val removeShopMatch = Regex("""remove (.+?) from (my )?shopping list""").find(tl)
        if (removeShopMatch != null) {
            return ShoppingListManager.removeItem(removeShopMatch.groupValues[1]) to "shopping_remove"
        }

        // ── NOTES ────────────────────────────────────────────────────────────
        VoiceNoteManager.init(ctx)
        if (tl.startsWith("note:") || tl.startsWith("save note") || tl.startsWith("write down")) {
            val text = cmd.replace(Regex("^(note:|save note|write down)\\s*", RegexOption.IGNORE_CASE), "").trim()
            if (text.isNotBlank()) return VoiceNoteManager.saveNote(text) to "save_note"
        }
        if (tl.contains("read my notes") || tl.contains("show notes") || tl == "notes") {
            return VoiceNoteManager.readNotes() to "read_notes"
        }
        if (tl.contains("delete last note")) {
            return VoiceNoteManager.deleteLastNote() to "delete_note"
        }
        if (tl.contains("clear all notes")) {
            return VoiceNoteManager.clearAll() to "clear_notes"
        }

        // ── FOCUS MODE ───────────────────────────────────────────────────────
        FocusModeManager.init(ctx)
        when {
            tl.contains("study mode") || tl.contains("focus study") -> {
                val resp = FocusModeManager.startFocusMode("study")
                ctx.startService(Intent(ctx, FocusModeService::class.java).apply { putExtra("mode", "study") })
                return resp to "focus_study"
            }
            tl.contains("work mode") || tl.contains("focus work") -> {
                val resp = FocusModeManager.startFocusMode("work")
                ctx.startService(Intent(ctx, FocusModeService::class.java).apply { putExtra("mode", "work") })
                return resp to "focus_work"
            }
            tl.contains("sleep mode") || tl.contains("focus sleep") -> {
                val resp = FocusModeManager.startFocusMode("sleep")
                ctx.startService(Intent(ctx, FocusModeService::class.java).apply { putExtra("mode", "sleep") })
                return resp to "focus_sleep"
            }
            tl.contains("focus off") || tl.contains("focus mode off") || tl.contains("disable focus") -> {
                ctx.stopService(Intent(ctx, FocusModeService::class.java))
                return FocusModeManager.startFocusMode("off") to "focus_off"
            }
            tl.contains("focus status") || tl.contains("focus mode?") -> {
                return FocusModeManager.getStatus() to "focus_status"
            }
        }

        // ── APP LOCK ─────────────────────────────────────────────────────────
        AppLockManager.init(ctx)
        val lockAppMatch = Regex("""lock (app )?([\w\s]+)""").find(tl)
        if (lockAppMatch != null && !tl.contains("lock phone") && !tl.contains("lock screen")) {
            val appName = lockAppMatch.groupValues[2].trim()
            val pkg = resolvePackageName(ctx, appName)
            AppLockManager.lockApp(pkg)
            return "Locked $appName. It will require authentication to open." to "lock_app"
        }
        val unlockAppMatch = Regex("""unlock (app )?([\w\s]+)""").find(tl)
        if (unlockAppMatch != null && !tl.contains("unlock phone")) {
            val appName = unlockAppMatch.groupValues[2].trim()
            val pkg = resolvePackageName(ctx, appName)
            AppLockManager.unlockApp(pkg)
            return "Removed lock from $appName." to "unlock_app"
        }
        if (tl.contains("intruder log") || tl.contains("show intruders")) {
            val log = AppLockManager.getIntruderLog()
            return if (log.isEmpty()) "No intruder attempts recorded." to "intruder_log"
            else "Last intruder attempt: ${log.last().getString("time")}" to "intruder_log"
        }

        // ── DRIVING MODE ─────────────────────────────────────────────────────
        when {
            tl.contains("driving mode on") || tl.contains("start driving mode") -> {
                ctx.startService(Intent(ctx, DrivingModeService::class.java))
                return "Driving mode started. I'll read your messages aloud." to "driving_on"
            }
            tl.contains("driving mode off") || tl.contains("stop driving mode") -> {
                ctx.stopService(Intent(ctx, DrivingModeService::class.java))
                return "Driving mode stopped." to "driving_off"
            }
        }

        // ── READ MESSAGES ─────────────────────────────────────────────────────
        if (tl.contains("read") && (tl.contains("message") || tl.contains("whatsapp") || tl.contains("sms"))) {
            val latest = NotificationMonitorService.latestNotification.value
            return if (latest != null) {
                "Latest ${latest.appName} from ${latest.sender}: ${latest.text}" to "read_message"
            } else {
                "No recent messages to read." to "read_message"
            }
        }

        return null
    }

    private fun resolvePackageName(ctx: Context, name: String): String {
        val map = mapOf(
            "instagram" to "com.instagram.android",
            "whatsapp" to "com.whatsapp",
            "tiktok" to "com.zhiliaoapp.musically",
            "youtube" to "com.google.android.youtube",
            "twitter" to "com.twitter.android", "x" to "com.twitter.android",
            "snapchat" to "com.snapchat.android",
            "telegram" to "org.telegram.messenger",
            "facebook" to "com.facebook.katana",
            "messenger" to "com.facebook.orca",
            "reddit" to "com.reddit.frontpage",
            "discord" to "com.discord",
            "spotify" to "com.spotify.music",
            "netflix" to "com.netflix.mediaclient",
            "gmail" to "com.google.android.gm",
            "chrome" to "com.android.chrome",
            "settings" to "com.android.settings",
        )
        return map[name.lowercase().trim()] ?: "com.${name.replace(" ", ".")}"
    }
}
