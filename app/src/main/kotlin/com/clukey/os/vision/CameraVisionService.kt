package com.clukey.os.vision

import android.content.Context
import android.graphics.Bitmap
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.clukey.os.utils.AppLogger
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * CameraVisionService — CameraX + MLKit pipeline.
 */
@OptIn(ExperimentalGetImage::class)
class CameraVisionService(private val context: Context) {

    private val TAG = "CameraVision"

    private val _detectedText = MutableStateFlow("")
    val detectedText: StateFlow<String> = _detectedText

    private val _detectedObjects = MutableStateFlow<List<DetectedObject>>(emptyList())
    val detectedObjects: StateFlow<List<DetectedObject>> = _detectedObjects

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val objectDetector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
    )

    private val barcodeScanner = BarcodeScanning.getClient()

    fun startCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView? = null) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView?.surfaceProvider)
                }
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { ia ->
                        ia.setAnalyzer(ContextCompat.getMainExecutor(context)) { imageProxy ->
                            analyzeFrame(imageProxy)
                        }
                    }
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, imageCapture, analysis
                )
                AppLogger.i(TAG, "CameraX started")
            } catch (e: Exception) {
                AppLogger.e(TAG, "CameraX start failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: run { imageProxy.close(); return }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        textRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                val text = visionText.text.trim()
                if (text.isNotBlank()) _detectedText.value = text
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    suspend fun recognizeTextFromBitmap(bitmap: Bitmap): String =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            textRecognizer.process(image)
                .addOnSuccessListener { cont.resume(it.text.trim()) }
                .addOnFailureListener { cont.resume("") }
        }

    suspend fun scanQRFromBitmap(bitmap: Bitmap): String? =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            barcodeScanner.process(image)
                .addOnSuccessListener { barcodes -> cont.resume(barcodes.firstOrNull()?.rawValue) }
                .addOnFailureListener { cont.resume(null) }
        }

    fun enableTorch(on: Boolean) { camera?.cameraControl?.enableTorch(on) }

    fun release() {
        textRecognizer.close()
        objectDetector.close()
        barcodeScanner.close()
    }
}
