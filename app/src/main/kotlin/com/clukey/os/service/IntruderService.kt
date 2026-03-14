package com.clukey.os.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.*
import android.util.Base64
import com.clukey.os.network.CloudSyncService
import com.clukey.os.utils.AppLogger
import kotlinx.coroutines.*
import java.nio.ByteBuffer

/**
 * IntruderService — takes a front-camera selfie on wrong unlock attempts
 * and sends the base64 image to the server → visible on dashboard.
 */
class IntruderService : Service() {

    private val TAG = "IntruderService"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var failedAttempts = 0

    companion object {
        const val ACTION_WRONG_UNLOCK = "com.clukey.os.WRONG_UNLOCK"
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT) {
                failedAttempts = 0
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))
        AppLogger.i(TAG, "IntruderService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_WRONG_UNLOCK) {
            failedAttempts++
            AppLogger.i(TAG, "Wrong unlock attempt #$failedAttempts")
            if (failedAttempts >= 2) captureIntruderSelfie()
        }
        return START_STICKY
    }

    private fun captureIntruderSelfie() {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val frontCamId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            } ?: return

            val imageReader = ImageReader.newInstance(640, 480, android.graphics.ImageFormat.JPEG, 1)
            imageReader.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                val buffer: ByteBuffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                image.close()

                val b64 = Base64.encodeToString(bytes, Base64.DEFAULT)
                scope.launch {
                    val ok = CloudSyncService.pushIntruderSelfie(b64)
                    AppLogger.i(TAG, "Intruder selfie sent: $ok")
                }
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
            AppLogger.e(TAG, "Intruder capture failed", e)
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(screenReceiver) } catch (e: Exception) {}
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
