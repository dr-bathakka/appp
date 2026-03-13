package com.clukey.os.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.clukey.os.service.AssistantService
import com.clukey.os.utils.AppLogger
import com.clukey.os.utils.PrefsManager

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        PrefsManager.init(applicationContext)
        AppLogger.i("MainActivity", "CluKey v5 started")

        // Start the main assistant service
        val intent = Intent(this, AssistantService::class.java)
        try {
            startForegroundService(intent)
        } catch (e: Exception) {
            AppLogger.e("MainActivity", "Failed to start AssistantService", e)
            Toast.makeText(this, "CluKey starting...", Toast.LENGTH_SHORT).show()
        }

        // Show status
        Toast.makeText(this, "CluKey AI Assistant running in background", Toast.LENGTH_LONG).show()
        finish() // Close activity — app runs as background service
    }
}
