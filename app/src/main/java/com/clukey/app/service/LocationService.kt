package com.clukey.app.service

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.*
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.clukey.app.network.CloudSyncService
import com.google.android.gms.location.*
import org.json.JSONObject

class LocationService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val CHANNEL_ID = "location_service"
    private val INTERVAL = 60_000L // 60 seconds

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { pushLocation(it) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        startForeground(3, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startLocationUpdates()
        return START_STICKY
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return

        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, INTERVAL)
            .setMinUpdateIntervalMillis(INTERVAL)
            .build()

        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    private fun pushLocation(location: Location) {
        val data = JSONObject().apply {
            put("lat", location.latitude)
            put("lng", location.longitude)
            put("accuracy", location.accuracy)
            put("timestamp", System.currentTimeMillis())
        }
        CloudSyncService(this).pushLocation(data)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Location Tracking", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CluKey")
            .setContentText("Tracking location...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .build()
    }

    override fun onDestroy() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
