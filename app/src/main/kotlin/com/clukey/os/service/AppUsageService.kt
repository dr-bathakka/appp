package com.clukey.os.service

import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import com.clukey.os.network.CloudSyncService
import com.clukey.os.utils.AppLogger
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

/**
 * AppUsageService — reads app usage stats (requires PACKAGE_USAGE_STATS permission).
 * Pushes daily usage to server every hour so dashboard can show "TikTok: 47 mins".
 */
class AppUsageService : Service() {

    private val TAG = "AppUsageService"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch {
            while (isActive) {
                pushUsage()
                delay(3_600_000L) // every hour
            }
        }
        return START_STICKY
    }

    private fun pushUsage() {
        try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val start = now - TimeUnit.HOURS.toMillis(24)

            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now)
                ?.filter { it.totalTimeInForeground > 60_000 } // at least 1 min
                ?.sortedByDescending { it.totalTimeInForeground }
                ?.take(20)
                ?: return

            val pm: PackageManager = packageManager
            val usageList = stats.map { s ->
                val appName = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(s.packageName, 0)).toString()
                } catch (e: Exception) { s.packageName }
                mapOf(
                    "package" to s.packageName,
                    "name"    to appName,
                    "minutes" to TimeUnit.MILLISECONDS.toMinutes(s.totalTimeInForeground)
                )
            }

            scope.launch { CloudSyncService.pushAppUsage(usageList) }
            AppLogger.i(TAG, "App usage pushed: ${usageList.size} apps")
        } catch (e: Exception) {
            AppLogger.e(TAG, "App usage failed", e)
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
