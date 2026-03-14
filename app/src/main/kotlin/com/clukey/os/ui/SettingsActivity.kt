package com.clukey.os.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.clukey.os.databinding.ActivitySettingsBinding
import com.clukey.os.network.CloudSyncService
import com.clukey.os.utils.AppLogger
import com.clukey.os.utils.PrefsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * SettingsActivity — configure server URL, API key, wake word,
 * and toggle features (overlay, HTTP server, notifications, auto-start).
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "CluKey Settings"

        loadSettings()
        setupSaveButton()
        setupTestButton()
        setupRegisterButton()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed(); return true
    }

    private fun loadSettings() {
        binding.apply {
            etServerUrl.setText(PrefsManager.serverUrl)
            etApiKey.setText(PrefsManager.apiKey)
            etWakeWord.setText(PrefsManager.wakeWord)
            etHttpPort.setText(PrefsManager.httpServerPort.toString())
            swWakeWord.isChecked     = PrefsManager.wakeWordEnabled
            swOverlay.isChecked      = PrefsManager.overlayEnabled
            swHttpServer.isChecked   = PrefsManager.httpServerEnabled
            swNotifRead.isChecked    = PrefsManager.notificationReadEnabled
            swAutoStart.isChecked    = PrefsManager.autoStartEnabled
        }
    }

    private fun setupSaveButton() {
        binding.btnSaveSettings.setOnClickListener {
            PrefsManager.serverUrl            = binding.etServerUrl.text.toString()
            PrefsManager.apiKey               = binding.etApiKey.text.toString()
            PrefsManager.wakeWord             = binding.etWakeWord.text.toString()
            PrefsManager.httpServerPort       = binding.etHttpPort.text.toString().toIntOrNull() ?: 8080
            PrefsManager.wakeWordEnabled      = binding.swWakeWord.isChecked
            PrefsManager.overlayEnabled       = binding.swOverlay.isChecked
            PrefsManager.httpServerEnabled    = binding.swHttpServer.isChecked
            PrefsManager.notificationReadEnabled = binding.swNotifRead.isChecked
            PrefsManager.autoStartEnabled     = binding.swAutoStart.isChecked

            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            AppLogger.i("Settings", "Settings saved — server=${PrefsManager.serverUrl}")
        }
    }

    private fun setupTestButton() {
        binding.btnTestConnection.setOnClickListener {
            CoroutineScope(Dispatchers.Main).launch {
                binding.txtConnectionResult.text = "Testing…"
                val result = CloudSyncService.getStatus()
                binding.txtConnectionResult.text = if (result.isSuccess)
                    "✓ Connected — ${result.getOrNull()?.status}"
                else
                    "✗ Failed — ${result.exceptionOrNull()?.message}"
            }
        }
    }

    private fun setupRegisterButton() {
        binding.btnRegisterDevice.setOnClickListener {
            CoroutineScope(Dispatchers.Main).launch {
                val result = CloudSyncService.registerDevice()
                binding.txtConnectionResult.text = if (result.isSuccess)
                    "✓ Registered — token: ${result.getOrNull()?.take(12)}…"
                else
                    "✗ Registration failed"
            }
        }
    }
}
