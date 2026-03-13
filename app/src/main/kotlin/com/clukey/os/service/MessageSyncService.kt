package com.clukey.os.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.clukey.os.network.CloudSyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MessageSyncService — intercepts all notifications and pushes messages to CluKey Brain.
 * Every WhatsApp/SMS/Telegram/Instagram message will appear in your laptop dashboard.
 *
 * REQUIRES: Notification Access permission
 *   Settings → Apps → Special App Access → Notification Access → CluKeyOS ✓
 */
class MessageSyncService : NotificationListenerService() {

    private val TAG   = "MsgSync"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // ✅ FIX: time-based dedup — key expires after 60s so same message not re-stored
    private val recentKeys = LinkedHashMap<String, Long>()
    private fun isDuplicate(key: String): Boolean {
        val now = System.currentTimeMillis()
        recentKeys.entries.removeIf { now - it.value > 60_000 } // expire old entries
        if (recentKeys.containsKey(key)) return true
        recentKeys[key] = now
        return false
    }
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    data class AppConfig(val name: String, val sync: Boolean = true)

    private val WATCHED = mapOf(
        "com.whatsapp"                     to AppConfig("WhatsApp"),
        "com.whatsapp.w4b"                 to AppConfig("WhatsApp Business"),
        "org.telegram.messenger"            to AppConfig("Telegram"),
        "org.telegram.messenger.web"        to AppConfig("Telegram"),
        "com.instagram.android"             to AppConfig("Instagram"),
        "com.facebook.orca"                 to AppConfig("Messenger"),
        "com.snapchat.android"              to AppConfig("Snapchat"),
        "com.discord"                       to AppConfig("Discord"),
        "com.google.android.apps.messaging" to AppConfig("Google SMS"),
        "com.samsung.android.messaging"     to AppConfig("Samsung SMS"),
        "com.android.mms"                   to AppConfig("SMS"),
        "com.microsoft.teams"               to AppConfig("Teams"),
        "com.slack"                         to AppConfig("Slack"),
        "com.google.android.gm"             to AppConfig("Gmail", sync = false),
    )

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val config = WATCHED[sbn.packageName] ?: return
        if (!config.sync) return

        val extras = sbn.notification?.extras ?: return
        val title  = extras.getString(Notification.EXTRA_TITLE) ?: return
        val text   = (extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString() ?: return
        if (text.isBlank()) return

        // De-duplicate with time expiry
        val key = "${sbn.packageName}|$title|$text"
        if (isDuplicate(key)) return

        val account  = sbn.notification?.extras?.getString("android.accountName") ?: "default"
        val threadId = sbn.notification?.extras?.getString("android.threadId") ?: sbn.groupKey ?: title
        val ts       = fmt.format(Date(sbn.postTime))

        Log.i(TAG, "[${config.name}] $title: ${text.take(60)}")

        scope.launch {
            try {
                CloudSyncService.pushMessage(
                    account   = account,
                    app       = config.name,
                    sender    = title,
                    text      = text,
                    timestamp = ts,
                    threadId  = threadId,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Push failed: ${e.message}")
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Connected — watching ${WATCHED.size} apps")
    }

    companion object {
        fun isEnabled(ctx: Context): Boolean {
            val listeners = android.provider.Settings.Secure.getString(
                ctx.contentResolver, "enabled_notification_listeners") ?: return false
            return listeners.contains(ComponentName(ctx, MessageSyncService::class.java).flattenToString())
        }
    }
}
