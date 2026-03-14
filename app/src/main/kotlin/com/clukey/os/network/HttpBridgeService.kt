package com.clukey.os.network

import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.clukey.os.CluKeyApp
import com.clukey.os.R
import com.clukey.os.engine.CommandExecutor
import com.clukey.os.utils.AppLogger
import com.clukey.os.utils.PrefsManager

/**
 * HttpBridgeService — foreground wrapper for HttpBridgeServer (NanoHTTPD).
 * Starts the local phone control server as a persistent foreground service.
 *
 * The cloud AI brain connects to this server via the phone's local IP
 * to send device control commands (open app, toggle wifi, etc.).
 */
class HttpBridgeService : LifecycleService() {

    companion object {
        private const val TAG = "HttpBridgeService"
        private const val NOTIF_ID = 1003
    }

    private var httpServer: HttpBridgeServer? = null
    private lateinit var executor: CommandExecutor

    override fun onCreate() {
        super.onCreate()
        executor = CommandExecutor(this)
        startForeground(NOTIF_ID, NotificationCompat.Builder(this, CluKeyApp.CHANNEL_HTTP)
            .setSmallIcon(R.drawable.ic_clukey_notif)
            .setContentTitle("CluKey HTTP Bridge")
            .setContentText("Local phone control server on :${PrefsManager.httpServerPort}")
            .setOngoing(true)
            .build()
        )
        startServer()
    }

    override fun onBind(intent: Intent): IBinder? { super.onBind(intent); return null }

    override fun onDestroy() {
        httpServer?.stop()
        executor.shutdown()
        super.onDestroy()
    }

    private fun startServer() {
        try {
            httpServer = HttpBridgeServer(PrefsManager.httpServerPort, executor)
            httpServer!!.start()
            AppLogger.i(TAG, "HTTP bridge started on port ${PrefsManager.httpServerPort}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "HTTP server failed", e)
        }
    }
}
