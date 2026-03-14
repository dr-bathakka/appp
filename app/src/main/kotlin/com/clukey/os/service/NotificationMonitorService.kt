package com.clukey.os.service

import android.app.Notification
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.clukey.os.utils.AppLogger
import com.clukey.os.utils.PrefsManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * ╔══════════════════════════════════════════════════════════════╗
 * ║   CluKey — NotificationMonitorService (GHOST RECORDER)      ║
 * ║                                                              ║
 * ║  Captures EVERY notification the moment it arrives —        ║
 * ║  saved to local storage AND synced to CluKey server.        ║
 * ║  Messages are NEVER lost even if the sender deletes them.   ║
 * ║                                                              ║
 * ║  Supported apps:                                             ║
 * ║    • WhatsApp (& WhatsApp Business)                         ║
 * ║    • Instagram                                               ║
 * ║    • Telegram (& Telegram X)                                ║
 * ║    • Facebook Messenger                                      ║
 * ║    • Snapchat                                                ║
 * ║    • Twitter / X DMs                                         ║
 * ║    • SMS / MMS                                               ║
 * ║    • Any app (catch-all mode)                               ║
 * ╚══════════════════════════════════════════════════════════════╝
 */
class NotificationMonitorService : NotificationListenerService() {

    companion object {
        private const val TAG = "CluKey.GhostRecorder"

        // ── Package names of monitored apps ────────────────────────────
        val SOCIAL_PACKAGES = mapOf(
            "com.whatsapp"                    to "WhatsApp",
            "com.whatsapp.w4b"               to "WhatsApp Business",
            "com.instagram.android"          to "Instagram",
            "org.telegram.messenger"         to "Telegram",
            "org.telegram.messenger.web"     to "Telegram",
            "org.thunderdog.challegram"      to "Telegram X",
            "com.facebook.orca"              to "Messenger",
            "com.snapchat.android"           to "Snapchat",
            "com.twitter.android"            to "Twitter/X",
            "com.discord"                    to "Discord",
            "com.linkedin.android"           to "LinkedIn",
            "com.viber.voip"                 to "Viber",
            "com.skype.raider"               to "Skype",
            "com.microsoft.teams"            to "Teams",
            // SMS apps
            "com.google.android.apps.messaging" to "SMS",
            "com.samsung.android.messaging"     to "SMS",
            "com.android.mms"                   to "SMS",
        )

        private val _latestNotification = MutableStateFlow<NotifData?>(null)
        val latestNotification: StateFlow<NotifData?> = _latestNotification

        var instance: NotificationMonitorService? = null
            private set

        // Local record file name
        const val RECORD_FILE = "ghost_messages.json"
        const val MAX_LOCAL_RECORDS = 5000
    }

    // ── Data model ──────────────────────────────────────────────────────────
    data class NotifData(
        val id: String,
        val packageName: String,
        val appName: String,
        val sender: String,
        val title: String,
        val text: String,
        val bigText: String,       // full expanded text if available
        val timestamp: Long,
        val timestampFormatted: String,
        val isDeleted: Boolean = false,
        val syncedToServer: Boolean = false
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    // ── Lifecycle ───────────────────────────────────────────────────────────
    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        AppLogger.i(TAG, "👻 Ghost Recorder connected — watching ${SOCIAL_PACKAGES.size} apps")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        AppLogger.i(TAG, "Ghost Recorder disconnected")
    }

    // ── CAPTURE: called the instant a notification arrives ──────────────────
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        val pkg = sbn.packageName ?: return

        // Only monitor social apps (or all apps if catch-all enabled)
        val appName = SOCIAL_PACKAGES[pkg]
            ?: if (PrefsManager.catchAllNotificationsEnabled) pkg else return

        val extras = sbn.notification.extras

        // Extract all available text fields
        val title    = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val text     = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
        val bigText  = extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString()?.trim() ?: ""
        val subText  = extras.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT)?.toString()?.trim() ?: ""
        val infoText = extras.getCharSequence(android.app.Notification.EXTRA_INFO_TEXT)?.toString()?.trim() ?: ""

        // Extract message lines (for bundled notifications like WhatsApp groups)
        val messages = extras.getCharSequenceArray(android.app.Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { it?.toString()?.trim() }
            ?: emptyList()

        // Build the best possible content string
        val fullContent = when {
            bigText.isNotBlank() && bigText != text -> bigText
            messages.isNotEmpty()                   -> messages.joinToString("\n")
            text.isNotBlank()                       -> text
            subText.isNotBlank()                    -> subText
            else                                    -> return  // nothing to record
        }

        // Skip our own app's notifications
        if (pkg == applicationContext.packageName) return

        val now = System.currentTimeMillis()
        val record = NotifData(
            id                 = "${pkg}_${sbn.id}_${now}",
            packageName        = pkg,
            appName            = appName,
            sender             = title,
            title              = title,
            text               = fullContent,
            bigText            = bigText,
            timestamp          = now,
            timestampFormatted = dateFormat.format(Date(now))
        )

        AppLogger.i(TAG, "👻 CAPTURED [$appName] $title: ${fullContent.take(80)}")

        // Update live flow
        _latestNotification.value = record

        // Persist + sync in background
        scope.launch {
            saveToLocalStorage(record)
            syncToServer(record)
        }
    }

    // ── When a notification is REMOVED (sender deleted it) ──────────────────
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName ?: return
        if (!SOCIAL_PACKAGES.containsKey(pkg)) return

        // Mark existing record as "deleted by sender" — we still keep the content
        scope.launch {
            markAsDeleted(pkg, sbn.id, sbn.postTime)
            AppLogger.i(TAG, "🗑 Sender deleted message from ${SOCIAL_PACKAGES[pkg]} — we kept it")
        }
    }

    // ── LOCAL STORAGE ────────────────────────────────────────────────────────
    /**
     * Saves the captured record to ghost_messages.json on device storage.
     * This is the permanent local copy — survives app restarts.
     */
    private fun saveToLocalStorage(record: NotifData) {
        try {
            val file = File(filesDir, RECORD_FILE)
            val array: JSONArray = if (file.exists()) {
                try { JSONArray(file.readText()) } catch (e: Exception) { JSONArray() }
            } else {
                JSONArray()
            }

            val entry = JSONObject().apply {
                put("id",                  record.id)
                put("package_name",        record.packageName)
                put("app_name",            record.appName)
                put("sender",              record.sender)
                put("text",                record.text)
                put("big_text",            record.bigText)
                put("timestamp",           record.timestamp)
                put("timestamp_formatted", record.timestampFormatted)
                put("deleted_by_sender",   false)
                put("synced",              false)
            }
            array.put(entry)

            // Trim to max records (keep newest)
            val trimmed = JSONArray()
            val start = maxOf(0, array.length() - MAX_LOCAL_RECORDS)
            for (i in start until array.length()) trimmed.put(array.get(i))

            file.writeText(trimmed.toString(2))
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to save locally: ${e.message}")
        }
    }

    /**
     * Marks a record as deleted-by-sender in local storage.
     * Content is preserved — only the flag changes.
     */
    private fun markAsDeleted(pkg: String, notifId: Int, postTime: Long) {
        try {
            val file = File(filesDir, RECORD_FILE)
            if (!file.exists()) return

            val array = JSONArray(file.readText())
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                // Match by package + approximate time (within 2 seconds)
                if (obj.getString("package_name") == pkg) {
                    val ts = obj.getLong("timestamp")
                    if (Math.abs(ts - postTime) < 2000) {
                        obj.put("deleted_by_sender", true)
                        obj.put("deleted_at", dateFormat.format(Date()))
                    }
                }
            }
            file.writeText(array.toString(2))
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to mark deleted: ${e.message}")
        }
    }

    // ── SERVER SYNC ──────────────────────────────────────────────────────────
    /**
     * Pushes the record to the CluKey brain server so it appears
     * on your PC in real time via clukey_pc.py.
     */
    private fun syncToServer(record: NotifData) {
        val serverUrl = PrefsManager.serverUrl.ifBlank { return }
        val apiKey    = PrefsManager.apiKey

        try {
            val payload = JSONObject().apply {
                put("account",  record.sender)
                put("app",      record.appName)
                put("sender",   record.sender)
                put("text",     record.text)
                put("timestamp", record.timestampFormatted)
                put("pkg",      record.packageName)
            }

            val url = java.net.URL("$serverUrl/messages/push")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                if (apiKey.isNotBlank()) setRequestProperty("X-CLUKEY-KEY", apiKey)
                connectTimeout = 5000
                readTimeout = 5000
            }

            conn.outputStream.use { it.write(payload.toString().toByteArray()) }

            val responseCode = conn.responseCode
            if (responseCode == 200) {
                markAsSynced(record.id)
                AppLogger.i(TAG, "✅ Synced to server: ${record.appName} / ${record.sender}")
            } else {
                AppLogger.w(TAG, "Server returned $responseCode for ${record.id}")
            }
            conn.disconnect()
        } catch (e: Exception) {
            // Network unavailable — record stays local, retry on next launch
            AppLogger.w(TAG, "Sync failed (will retry): ${e.message}")
        }
    }

    /** Marks a record as successfully synced to the server */
    private fun markAsSynced(recordId: String) {
        try {
            val file = File(filesDir, RECORD_FILE)
            if (!file.exists()) return
            val array = JSONArray(file.readText())
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                if (obj.getString("id") == recordId) {
                    obj.put("synced", true)
                    break
                }
            }
            file.writeText(array.toString(2))
        } catch (e: Exception) { /* non-critical */ }
    }

    // ── RETRY UNSYNCED on reconnect ──────────────────────────────────────────
    /**
     * Called by AssistantService on startup to retry any messages
     * that failed to sync (e.g. phone was offline).
     */
    fun retrySyncUnsyncedMessages() {
        scope.launch {
            try {
                val file = File(filesDir, RECORD_FILE)
                if (!file.exists()) return@launch
                val array = JSONArray(file.readText())
                var retried = 0
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    if (!obj.getBoolean("synced")) {
                        val record = NotifData(
                            id                 = obj.getString("id"),
                            packageName        = obj.getString("package_name"),
                            appName            = obj.getString("app_name"),
                            sender             = obj.getString("sender"),
                            title              = obj.getString("sender"),
                            text               = obj.getString("text"),
                            bigText            = obj.optString("big_text", ""),
                            timestamp          = obj.getLong("timestamp"),
                            timestampFormatted = obj.getString("timestamp_formatted")
                        )
                        syncToServer(record)
                        retried++
                        delay(200) // don't hammer server
                    }
                }
                if (retried > 0) AppLogger.i(TAG, "↩ Retried $retried unsynced messages")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Retry failed: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
