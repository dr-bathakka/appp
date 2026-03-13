package com.clukey.os.service

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.*
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.clukey.os.CluKeyApp
import com.clukey.os.R
import com.clukey.os.network.CloudSyncService
import com.clukey.os.utils.AppLogger
import com.google.android.gms.location.*
import kotlinx.coroutines.*

/**
 * LocationService — streams GPS location to the server every 60s.
 * Dashboard shows live map with phone location.
 */
class LocationService : Service() {

    private val TAG = "LocationService"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { pushLocation(it) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        startForeground(2003, buildNotification())
        AppLogger.i(TAG, "LocationService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            val req = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 60_000L)
                .setMinUpdateIntervalMillis(60_000L)
                .build()
            fusedLocationClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
        } else {
            AppLogger.e(TAG, "Location permission not granted")
        }
        return START_STICKY
    }

    private fun pushLocation(location: Location) {
        scope.launch {
            try {
                CloudSyncService.pushLocation(
                    lat      = location.latitude,
                    lng      = location.longitude,
                    accuracy = location.accuracy,
                    speed    = location.speed
                )
                AppLogger.i(TAG, "Location pushed: ${location.latitude},${location.longitude}")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Location push failed", e)
            }
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CluKeyApp.CHANNEL_ASSISTANT)
            .setSmallIcon(R.drawable.ic_clukey_notif)
            .setContentTitle("CluKey")
            .setContentText("Location tracking active…")
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
