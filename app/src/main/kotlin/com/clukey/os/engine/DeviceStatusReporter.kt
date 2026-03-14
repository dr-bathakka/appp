package com.clukey.os.engine

import android.content.Context
import com.clukey.os.network.CloudSyncService
import com.clukey.os.service.NotificationMonitorService
import com.clukey.os.service.ScreenReaderService
import com.clukey.os.utils.AppLogger
import com.clukey.os.utils.DeviceUtils
import kotlinx.coroutines.*

/**
 * DeviceStatusReporter — collects and reports device state to cloud AI.
 * Reports every 60 seconds.
 */
class DeviceStatusReporter(private val context: Context) {

    private val TAG = "DeviceStatusReporter"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var running = false

    fun start() {
        running = true
        scope.launch { loop() }
        AppLogger.i(TAG, "DeviceStatusReporter started")
    }

    fun stop() {
        running = false
        scope.cancel()
    }

    private suspend fun loop() {
        while (running) {
            try {
                report()
            } catch (e: Exception) {
                AppLogger.e(TAG, "Report error", e)
            }
            delay(60_000)
        }
    }

    private suspend fun report() {
        val battery    = DeviceUtils.getBatteryLevel(context)
        val network    = DeviceUtils.getNetworkState(context)
        val foreground = ScreenReaderService.foregroundApp.value
            .ifBlank { DeviceUtils.getForegroundApp(context) }
        val lastNotif  = NotificationMonitorService.latestNotification.value

        AppLogger.d(TAG, "Status report — bat=$battery net=$network fg=$foreground notif=${lastNotif?.title}")
        CloudSyncService.pushDeviceInfo()
    }
}
