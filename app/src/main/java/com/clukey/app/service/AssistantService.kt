package com.clukey.app.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.clukey.app.network.CloudSyncService

class AssistantService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAllServices()
        return START_STICKY
    }

    private fun startAllServices() {
        // Start Device Health Monitor
        startService(Intent(this, DeviceHealthService::class.java))

        // Start Location Tracker
        startService(Intent(this, LocationService::class.java))

        // Start Intruder Detection
        startService(Intent(this, IntruderService::class.java))

        // Start Rules Tick Engine
        startService(Intent(this, RulesTickService::class.java))

        // Start Cloud Sync
        CloudSyncService(this).startPolling()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
