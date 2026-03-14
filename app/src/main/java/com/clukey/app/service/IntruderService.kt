package com.clukey.app.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.*
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.clukey.app.network.CloudSyncService
import org.json.JSONObject
import java.nio.ByteBuffer

class IntruderService : Service() {

    private val CHANNEL_ID = "intruder_service"
    private var failedAttempts = 0

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT) {
                failedAttempts = 0 // Reset on successful unlock
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(4, buildNotification())

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Called externally when wrong PIN detected
        if (intent?.action == "WRONG_UNLOCK") {
            failedAttempts++
            if (failedAttempts >= 2) {
                captureIntruderSelfie()
            }
        }
        return START_STICKY
    }

    private fun captureIntruderSelfie() {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            // Find front camera
            val frontCamId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            } ?: return

            val imageReader = ImageReader.newInstance(640, 480, android.graphics.ImageFormat.JPEG, 1)
            imageReader.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                val buffer: ByteBuffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                image.close()

                val base64Image = Base64.encodeToString(bytes, Base64.DEFAULT)
                sendIntruderAlert(base64Image)
            }, Handler(Looper.getMainLooper()))

            cameraManager.openCamera(frontCamId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    captureRequest.addTarget(imageReader.surface)
                    camera.createCaptureSession(listOf(imageReader.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                session.capture(captureRequest.build(), null, null)
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) {}
                        }, null)
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close() }
                override fun onError(camera: CameraDevice, error: Int) { camera.close() }
            }, Handler(Looper.getMainLooper()))

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun sendIntruderAlert(imageBase64: String) {
        val data = JSONObject().apply {
            put("type", "intruder_selfie")
            put("image", imageBase64)
            put("timestamp", System.currentTimeMillis())
            put("attempts", failedAttempts)
        }
        CloudSyncService(this).pushIntruderAlert(data)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Intruder Detection", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CluKey Security")
            .setContentText("Intruder detection active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
    }

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
