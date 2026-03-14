package com.clukey.app.engine

import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import com.clukey.app.network.CloudSyncService
import com.clukey.app.service.IntruderService
import org.json.JSONObject

class CommandExecutor(private val context: Context) {

    fun execute(command: String, params: JSONObject?): String {
        return when (command) {

            // --- CALLS & SMS ---
            "make_call" -> {
                val number = params?.optString("number", "") ?: ""
                if (number.isNotEmpty()) {
                    val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                    "Calling $number"
                } else "No number provided"
            }

            "send_sms" -> {
                val number = params?.optString("number", "") ?: ""
                val message = params?.optString("message", "") ?: ""
                if (number.isNotEmpty() && message.isNotEmpty()) {
                    val smsManager = SmsManager.getDefault()
                    smsManager.sendTextMessage(number, null, message, null, null)
                    "SMS sent to $number"
                } else "Missing number or message"
            }

            // --- AUDIO ---
            "set_volume" -> {
                val level = params?.optInt("level", 50) ?: 50
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
                val vol = (level / 100.0 * maxVol).toInt()
                audioManager.setStreamVolume(AudioManager.STREAM_RING, vol, 0)
                "Volume set to $level%"
            }

            "silent_mode" -> {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (nm.isNotificationPolicyAccessGranted) {
                    audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
                    "Silent mode enabled"
                } else "DND permission not granted"
            }

            "normal_mode" -> {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                "Normal mode enabled"
            }

            // --- CLIPBOARD ---
            "set_clipboard" -> {
                val text = params?.optString("text", "") ?: ""
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("CluKey", text))
                "Clipboard set"
            }

            "get_clipboard" -> {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: "Clipboard empty"
            }

            // --- PANIC MODE ---
            "panic" -> {
                // Lock + send location + notify trusted contact
                val contactNumber = params?.optString("contact", "") ?: ""
                if (contactNumber.isNotEmpty()) {
                    val smsManager = SmsManager.getDefault()
                    smsManager.sendTextMessage(
                        contactNumber, null,
                        "🚨 CluKey PANIC triggered! User needs help.",
                        null, null
                    )
                }
                CloudSyncService(context).pushAlert(
                    JSONObject().apply {
                        put("type", "panic")
                        put("message", "PANIC MODE TRIGGERED")
                        put("timestamp", System.currentTimeMillis())
                    }
                )
                "Panic mode activated"
            }

            // --- INTRUDER ---
            "trigger_intruder_check" -> {
                val intent = Intent(context, IntruderService::class.java)
                intent.action = "WRONG_UNLOCK"
                context.startService(intent)
                "Intruder check triggered"
            }

            // --- TORCH ---
            "torch_on" -> {
                toggleTorch(true)
                "Torch on"
            }
            "torch_off" -> {
                toggleTorch(false)
                "Torch off"
            }

            else -> "Unknown command: $command"
        }
    }

    private fun toggleTorch(on: Boolean) {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, on)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
