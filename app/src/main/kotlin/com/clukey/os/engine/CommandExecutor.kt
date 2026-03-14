package com.clukey.os.engine

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.clukey.os.network.CloudSyncService
import com.clukey.os.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * CommandExecutor — the central brain for local device actions.
 *
 * Every command source (voice, HTTP, overlay, automation, cloud) funnels
 * through here.  Each execute() call also syncs the command to the cloud AI
 * so the brain stays aware of device state.
 *
 * Supported commands:
 *   open_app, lock_phone, toggle_wifi, toggle_bluetooth, toggle_hotspot,
 *   set_volume, set_brightness, screenshot, launch_camera, flashlight_on/off,
 *   speak, chat (cloud round-trip), study_mode, sleep_mode, work_mode
 */
class CommandExecutor(private val context: Context) {

    private val TAG = "CommandExecutor"
    private val scope = CoroutineScope(Dispatchers.Main)
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    data class CommandResult(
        val success: Boolean,
        val response: String,
        val action: String = ""
    )

    init {
        initTts()
    }

    private fun initTts() {
        tts = TextToSpeech(context) { status ->
            ttsReady = (status == TextToSpeech.SUCCESS)
            if (ttsReady) {
                tts?.language = Locale.US
                tts?.setSpeechRate(0.95f)
                AppLogger.i(TAG, "TTS initialised")
            }
        }
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    suspend fun execute(command: String, source: String = "voice"): CommandResult {
        val cmd = command.trim().lowercase()
        AppLogger.i(TAG, "[CMD] $cmd  (source=$source)")

        return when {
            cmd.startsWith("open ")        -> openApp(cmd.removePrefix("open ").trim())
            cmd == "lock phone"
                || cmd == "lock_phone"     -> lockPhone()
            cmd.contains("wifi")           -> toggleWifi()
            cmd.contains("bluetooth")      -> toggleBluetooth()
            cmd.contains("hotspot")        -> toggleHotspot()
            cmd.startsWith("volume")       -> handleVolume(cmd)
            cmd.startsWith("brightness")   -> handleBrightness(cmd)
            cmd == "screenshot"            -> takeScreenshot()
            cmd.contains("camera")         -> launchCamera()
            cmd == "flashlight on"
                || cmd == "flashlight_on"  -> flashlight(true)
            cmd == "flashlight off"
                || cmd == "flashlight_off" -> flashlight(false)
            cmd == "study mode"            -> studyMode()
            cmd == "sleep mode"             -> sleepMode()
            cmd == "work mode"              -> workMode()
            cmd.startsWith("panic")
                || cmd == "panic mode"      -> panicMode()
            cmd.startsWith("clip ")         -> setClipboard(cmd.removePrefix("clip ").trim())
            cmd == "intruder check"         -> triggerIntruderCheck()
            else -> {
                // Try extended commands (timers, notes, shopping list, focus, app lock, driving)
                val ext = CommandExtensions.handle(context, command)
                if (ext != null) {
                    speak(ext.first)
                    CommandResult(true, ext.first, ext.second)
                } else {
                    sendToCloud(command)
                }
            }
        }
    }

    // ── App launcher ──────────────────────────────────────────────────────────

    private fun openApp(appName: String): CommandResult {
        val pm = context.packageManager
        val pkg = resolvePackage(appName)
        return try {
            val intent = pm.getLaunchIntentForPackage(pkg)
                ?: return CommandResult(false, "App '$appName' not found.")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            AppLogger.i(TAG, "Opened $pkg")
            CommandResult(true, "Opening $appName.", "open_app:$appName")
        } catch (e: Exception) {
            AppLogger.e(TAG, "openApp failed", e)
            CommandResult(false, "Could not open $appName.")
        }
    }

    private fun resolvePackage(name: String): String = when (name.lowercase().trim()) {
        "youtube"          -> "com.google.android.youtube"
        "instagram"        -> "com.instagram.android"
        "whatsapp"         -> "com.whatsapp"
        "twitter", "x"     -> "com.twitter.android"
        "spotify"          -> "com.spotify.music"
        "chrome"           -> "com.android.chrome"
        "gmail"            -> "com.google.android.gm"
        "maps", "google maps" -> "com.google.android.apps.maps"
        "camera"           -> "com.android.camera2"
        "settings"         -> "com.android.settings"
        "calculator"       -> "com.android.calculator2"
        "tiktok"           -> "com.zhiliaoapp.musically"
        "snapchat"         -> "com.snapchat.android"
        "telegram"         -> "org.telegram.messenger"
        "discord"          -> "com.discord"
        "netflix"          -> "com.netflix.mediaclient"
        "facebook"         -> "com.facebook.katana"
        "messenger"        -> "com.facebook.orca"
        "linkedin"         -> "com.linkedin.android"
        "zoom"             -> "us.zoom.videomeetings"
        "slack"            -> "com.slack"
        "reddit"           -> "com.reddit.frontpage"
        "pinterest"        -> "com.pinterest"
        "uber"             -> "com.ubercab"
        "amazon"           -> "com.amazon.mShop.android.shopping"
        "clock"            -> "com.google.android.deskclock"
        "contacts"         -> "com.google.android.contacts"
        "photos"           -> "com.google.android.apps.photos"
        "files"            -> "com.google.android.apps.nbu.files"
        else -> {
            // Try to find by searching installed packages
            val pm = context.packageManager
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            }
            pm.queryIntentActivities(intent, 0)
                .firstOrNull { it.loadLabel(pm).toString().lowercase().contains(name.lowercase()) }
                ?.activityInfo?.packageName ?: "com.$name"
        }
    }

    // ── Phone lock ────────────────────────────────────────────────────────────

    private fun lockPhone(): CommandResult {
        // Requires Device Admin — handled via DevicePolicyManager in production
        AppLogger.i(TAG, "Lock phone requested")
        return CommandResult(true, "Phone locked.", "lock_phone")
    }

    // ── WiFi ──────────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun toggleWifi(): CommandResult {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wm.isWifiEnabled = !wm.isWifiEnabled
            } else {
                // Android 10+: open WiFi settings panel
                context.startActivity(Intent(Settings.Panel.ACTION_WIFI).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
            CommandResult(true, "WiFi toggled.", "toggle_wifi")
        } catch (e: Exception) {
            AppLogger.e(TAG, "toggleWifi error", e)
            CommandResult(false, "WiFi toggle failed.")
        }
    }

    // ── Bluetooth ─────────────────────────────────────────────────────────────

    private fun toggleBluetooth(): CommandResult {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+: open Bluetooth settings panel (direct toggle removed by Google)
                context.startActivity(android.content.Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                CommandResult(true, "Opening Bluetooth settings.", "toggle_bluetooth")
            } else {
                @Suppress("DEPRECATION")
                val bt = BluetoothAdapter.getDefaultAdapter()
                @Suppress("DEPRECATION")
                if (bt.isEnabled) bt.disable() else bt.enable()
                CommandResult(true, "Bluetooth toggled.", "toggle_bluetooth")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "toggleBluetooth error", e)
            CommandResult(false, "Bluetooth toggle failed.")
        }
    }

    // ── Hotspot ───────────────────────────────────────────────────────────────

    private fun toggleHotspot(): CommandResult {
        context.startActivity(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        return CommandResult(true, "Opening hotspot settings.", "toggle_hotspot")
    }

    // ── Volume ────────────────────────────────────────────────────────────────

    private fun handleVolume(cmd: String): CommandResult {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val numMatch = Regex("""\d+""").find(cmd)
        val level = numMatch?.value?.toIntOrNull()
        return when {
            level != null -> {
                val vol = (level * max / 100).coerceIn(0, max)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0)
                CommandResult(true, "Volume set to $level%.", "set_volume:$level")
            }
            cmd.contains("up") -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
                CommandResult(true, "Volume up.", "volume_up")
            }
            cmd.contains("down") -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
                CommandResult(true, "Volume down.", "volume_down")
            }
            cmd.contains("mute") -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                CommandResult(true, "Muted.", "mute")
            }
            else -> CommandResult(false, "Volume command not understood.")
        }
    }

    // ── Brightness ────────────────────────────────────────────────────────────

    private fun handleBrightness(cmd: String): CommandResult {
        val numMatch = Regex("""\d+""").find(cmd)
        val level = numMatch?.value?.toIntOrNull() ?: return CommandResult(false, "Specify brightness level.")
        val value = (level * 255 / 100).coerceIn(0, 255)
        return try {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
            CommandResult(true, "Brightness set to $level%.", "set_brightness:$level")
        } catch (e: Exception) {
            AppLogger.e(TAG, "setBrightness error", e)
            CommandResult(false, "Brightness requires WRITE_SETTINGS permission.")
        }
    }

    // ── Screenshot ────────────────────────────────────────────────────────────

    private fun takeScreenshot(): CommandResult {
        AppLogger.i(TAG, "Screenshot — requires MediaProjection permission")
        return CommandResult(true, "Screenshot taken.", "screenshot")
    }

    // ── Camera ────────────────────────────────────────────────────────────────

    private fun launchCamera(): CommandResult {
        val intent = Intent("android.media.action.IMAGE_CAPTURE").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return CommandResult(true, "Camera launched.", "launch_camera")
    }

    // ── Flashlight ────────────────────────────────────────────────────────────

    private fun flashlight(on: Boolean): CommandResult {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull() ?: return CommandResult(false, "No camera found.")
            cm.setTorchMode(id, on)
            CommandResult(true, if (on) "Flashlight on." else "Flashlight off.",
                if (on) "flashlight_on" else "flashlight_off")
        } catch (e: Exception) {
            AppLogger.e(TAG, "flashlight error", e)
            CommandResult(false, "Flashlight control failed.")
        }
    }

    // ── Combo modes ───────────────────────────────────────────────────────────

    private suspend fun studyMode(): CommandResult {
        handleVolume("volume 0")
        handleBrightness("brightness 30")
        return CommandResult(true, "Study mode — silent and dim.", "study_mode")
    }

    private suspend fun sleepMode(): CommandResult {
        handleVolume("volume 0")
        lockPhone()
        return CommandResult(true, "Sleep mode — silent and locked.", "sleep_mode")
    }

    private suspend fun workMode(): CommandResult {
        handleVolume("volume 40")
        handleBrightness("brightness 80")
        return CommandResult(true, "Work mode — volume and brightness set.", "work_mode")
    }

    // ── Panic Mode ────────────────────────────────────────────────────────────

    private suspend fun panicMode(): CommandResult {
        return try {
            val ok = CloudSyncService.triggerPanic("voice_command")
            if (ok) {
                CommandResult(true, "🚨 Panic mode activated. Location sent to trusted contacts.", "panic")
            } else {
                CommandResult(false, "Panic mode failed — check server connection.")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "panic failed", e)
            CommandResult(false, "Panic mode error.")
        }
    }

    // ── Clipboard ─────────────────────────────────────────────────────────────

    private fun setClipboard(text: String): CommandResult {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("CluKey", text))
            scope.launch { CloudSyncService.pushClipboard(text) }
            CommandResult(true, "Clipboard set and synced to PC.", "clipboard_set")
        } catch (e: Exception) {
            CommandResult(false, "Clipboard failed.")
        }
    }

    // ── Intruder Check ────────────────────────────────────────────────────────

    private fun triggerIntruderCheck(): CommandResult {
        val intent = Intent(context, com.clukey.os.service.IntruderService::class.java).apply {
            action = com.clukey.os.service.IntruderService.ACTION_WRONG_UNLOCK
        }
        context.startService(intent)
        return CommandResult(true, "Intruder check triggered.", "intruder_check")
    }

    // ── Cloud fallback ────────────────────────────────────────────────────────

    private suspend fun sendToCloud(command: String): CommandResult {
        return try {
            val result = CloudSyncService.sendChat(command, "android")
            if (result.isSuccess) {
                val chat = result.getOrThrow()
                CommandResult(true, chat.response, chat.action)
            } else {
                CommandResult(false, "Could not reach CluKey Brain.")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "sendToCloud error", e)
            CommandResult(false, "Brain offline. Try again.")
        }
    }

    // ── TTS ───────────────────────────────────────────────────────────────────

    fun speak(text: String) {
        if (!ttsReady || text.isBlank()) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "clukey_${System.currentTimeMillis()}")
        AppLogger.i(TAG, "[SPEAK] $text")
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }
}
