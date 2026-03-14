package com.clukey.os.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.IBinder
import android.util.Log
import com.clukey.os.network.CloudSyncService
import com.clukey.os.security.AppLockManager
import com.clukey.os.utils.AppLogger
import com.clukey.os.utils.PrefsManager
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * IntruderCameraService — silently captures front camera photo when:
 *  • Phone unlock fails 3+ times
 *  • App lock is bypassed attempt (wrong PIN/pattern)
 *
 * Photo is:
 *  1. Saved to /data/data/com.clukey.os/files/intruders/
 *  2. Uploaded to CluKey server as intruder_photo
 *  3. Alert pushed to dashboard
 *
 * Uses Camera2 API (no preview needed — completely silent capture)
 *
 * NOTE: Requires CAMERA permission
 */
class IntruderCameraService : Service() {

    companion object {
        const val TAG = "IntruderCamera"
        const val ACTION_CAPTURE = "com.clukey.os.INTRUDER_CAPTURE"
        const val EXTRA_REASON = "reason"

        fun capture(ctx: Context, reason: String = "wrong_unlock") {
            val intent = Intent(ctx, IntruderCameraService::class.java).apply {
                action = ACTION_CAPTURE
                putExtra(EXTRA_REASON, reason)
            }
            ctx.startService(intent)
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var cameraDevice: CameraDevice? = null
    private var imageReader: ImageReader? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val reason = intent?.getStringExtra(EXTRA_REASON) ?: "unknown"
        AppLogger.w(TAG, "🚨 Intruder capture triggered: $reason")
        scope.launch {
            captureIntruderPhoto(reason)
            delay(5000) // give time to upload
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private suspend fun captureIntruderPhoto(reason: String) {
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

            // Find front camera
            val frontCameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            } ?: run {
                AppLogger.w(TAG, "No front camera found")
                return
            }

            val reader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2)
            imageReader = reader

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val outputDir = File(filesDir, "intruders").apply { mkdirs() }
            val outputFile = File(outputDir, "intruder_$timestamp.jpg")

            reader.setOnImageAvailableListener({ imgReader ->
                val image = imgReader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    outputFile.writeBytes(bytes)
                    AppLogger.i(TAG, "📸 Intruder photo saved: ${outputFile.name}")
                    // Log to AppLockManager
                    AppLockManager.logIntruder(outputFile.absolutePath, reason)
                    // Upload to server
                    scope.launch { uploadIntruderPhoto(outputFile, reason, timestamp) }
                } finally {
                    image.close()
                }
            }, null)

            // Open camera and capture
            cameraManager.openCamera(frontCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    val surface = reader.surface
                    val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(surface)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        set(CaptureRequest.JPEG_QUALITY, 85)
                    }
                    camera.createCaptureSession(listOf(surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                session.capture(captureRequest.build(), null, null)
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                AppLogger.e(TAG, "Camera session config failed")
                                camera.close()
                            }
                        }, null)
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close() }
                override fun onError(camera: CameraDevice, error: Int) {
                    AppLogger.e(TAG, "Camera error: $error")
                    camera.close()
                }
            }, null)

        } catch (e: SecurityException) {
            AppLogger.e(TAG, "Camera permission denied: ${e.message}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Intruder capture failed: ${e.message}")
        }
    }

    private fun uploadIntruderPhoto(file: File, reason: String, timestamp: String) {
        try {
            val serverUrl = PrefsManager.serverUrl.ifBlank { return }
            val apiKey = PrefsManager.apiKey
            val base64 = android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)

            val payload = JSONObject().apply {
                put("timestamp", timestamp)
                put("reason", reason)
                put("photo_base64", base64)
                put("filename", file.name)
                put("failed_attempts", AppLockManager.failedAttempts)
            }

            val url = java.net.URL("$serverUrl/security/intruder_photo")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                if (apiKey.isNotBlank()) setRequestProperty("X-CLUKEY-KEY", apiKey)
                connectTimeout = 10000
                readTimeout = 10000
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            val code = conn.responseCode
            AppLogger.i(TAG, "Intruder photo upload: HTTP $code")
            conn.disconnect()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Upload failed: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraDevice?.close()
        imageReader?.close()
        scope.cancel()
    }
}
