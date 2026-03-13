package com.clukey.os.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.*
import com.clukey.os.network.CloudSyncService
import com.clukey.os.utils.AppLogger
import kotlinx.coroutines.*

/**
 * RulesTickService — evaluates IF/THEN automation rules every 60s.
 * Rules are stored on the server and fetched here.
 * Example rules: IF battery < 20% THEN silent mode
 *                IF time == 22:00 THEN lock phone
 */
class RulesTickService : Service() {

    private val TAG = "RulesTickService"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val INTERVAL = 60_000L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch {
            while (isActive) {
                try { tickRules() } catch (e: Exception) { AppLogger.e(TAG, "tick failed", e) }
                delay(INTERVAL)
            }
        }
        return START_STICKY
    }

    private suspend fun tickRules() {
        val result = CloudSyncService.tickRules()
        val fired = try { result.getAsJsonArray("fired") } catch (e: Exception) { null }
        if (fired != null && fired.size() > 0) {
            AppLogger.i(TAG, "Rules fired: ${fired.size()}")
        }

        // Also check battery-triggered rules locally
        val bm = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val battery = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (battery <= 20) {
            // Trigger silent if rule exists — server handles the logic
            AppLogger.i(TAG, "Low battery: $battery% — server notified")
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
