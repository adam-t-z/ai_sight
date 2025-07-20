package com.adamtz.ai_sight_android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.adamtz.ai_sight_android.databinding.ActivityMoneyCountingBinding
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.adamtz.ai_sight_android.Constants.MONEY_MODEL_PATH
import com.adamtz.ai_sight_android.Constants.MONEY_LABELS_PATH

class MoneyCountingActivity : AppCompatActivity(), Detector.DetectorListener, TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMoneyCountingBinding
    private val isFrontCamera = false
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var detector: Detector
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var tts: TextToSpeech
    private val handler = Handler(Looper.getMainLooper())
    private var totalAmountToSpeak: String = "0"
    private var isFlashOn = false
    private var noMoneyDetectedTimestamp = 0L
    private var lastSpeakTime = 0L
    private val speakCooldownMillis = 4000L
    private var isMoneyDetected = false
    private val repeatSpeakInterval = 1000L
    private val repeatSpeakHandler = Handler(Looper.getMainLooper())
    private var tapCount = 0
    private var lastTapTime = 0L
    private val tripleTapTimeout = 1000L
    private val repeatSpeakRunnable = object : Runnable {
        override fun run() {
            val currentTime = SystemClock.uptimeMillis()
            if (isMoneyDetected && totalAmountToSpeak != "0" &&
                (currentTime - lastSpeakTime) >= speakCooldownMillis) {

                val amountDouble = totalAmountToSpeak.toDoubleOrNull() ?: 0.0
                val formattedAmount = if (amountDouble % 1.0 == 0.0) {
                    amountDouble.toInt().toString()
                } else {
                    String.format("%.2f", amountDouble)
                }
                val textToSpeak = "$formattedAmount dinars"
                tts.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, null, "MoneyCountID")
                lastSpeakTime = currentTime
            }
            repeatSpeakHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMoneyCountingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        detector = Detector(baseContext, MONEY_MODEL_PATH, MONEY_LABELS_PATH, this)
        detector.setup()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        tts = TextToSpeech(this, this)

        // Triple tap listener to handle exit and cleanup
        binding.root.setOnClickListener {
            val currentTime = SystemClock.uptimeMillis()
            tapCount = if (currentTime - lastTapTime > tripleTapTimeout) 1 else tapCount + 1
            lastTapTime = currentTime

            if (tapCount == 3) {
                cleanupResources()  // Perform cleanup before exiting
                val intent = Intent(this, HomeActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                startActivity(intent)
                finish()  // Finish the current activity
                Log.d("MoneyCountingActivity", "Triple tap detected, starting HomeActivity")
            }
        }
    }

    private fun cleanupResources() {
        try {
            // Unbind all camera use cases to free camera resources
            cameraProvider?.unbindAll()

            // Clear image analysis and stop TTS
            imageAnalyzer?.clearAnalyzer()
            cameraExecutor.shutdownNow()

            // Turn off the flashlight if it's on
            if (isFlashOn) {
                toggleFlash(false)
            }

            // Stop TTS and release resources
            tts.stop()
            tts.shutdown()

            // Remove pending callbacks to avoid memory leaks
            handler.removeCallbacksAndMessages(null)
            repeatSpeakHandler.removeCallbacks(repeatSpeakRunnable)

        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }

    private fun toggleFlash(turnOn: Boolean) {
        camera?.cameraControl?.enableTorch(turnOn)
        isFlashOn = turnOn
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupResources()  // Ensure cleanup when the activity is destroyed
    }

    override fun onPause() {
        super.onPause()
        cleanupResources()  // Ensure cleanup when the activity is paused
    }

    override fun onStop() {
        super.onStop()
        cleanupResources()  // Ensure cleanup when the activity is stopped
    }

    private fun startCamera() {
        // Get an instance of the camera provider
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                // Initialize cameraProvider and bind use cases
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                // Log the error if camera initialization fails
                Log.e(TAG, "Error initializing camera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this)) // Run on main thread
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")
        val rotation = binding.viewFinder.display.rotation
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        val targetResolution = android.util.Size(1280, 960) // 4:3 aspect ratio resolution

        preview = Preview.Builder()
            .setTargetResolution(targetResolution)
            .setTargetRotation(rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetResolution(targetResolution)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(rotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
            val bitmapBuffer = Bitmap.createBitmap(
                imageProxy.width,
                imageProxy.height,
                Bitmap.Config.ARGB_8888
            )
            imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
            imageProxy.close()

            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                if (isFrontCamera) {
                    postScale(-1f, 1f, imageProxy.width.toFloat(), imageProxy.height.toFloat())
                }
            }

            val rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height,
                matrix, true
            )

            detector.detect(rotatedBitmap)
        }

        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer
            )
            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)

            // Turn on flashlight immediately after camera is bound
            camera?.cameraControl?.enableTorch(true)
            isFlashOn = true

        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "TTS language not supported or missing data")
            }
        } else {
            Log.e(TAG, "TTS initialization failed")
        }
    }

    override fun onEmptyDetect() {
        runOnUiThread {
            binding.overlay.setResults(emptyList())
            binding.overlay.invalidate()
            binding.totalCountLabel.text = "0 dinars"
            totalAmountToSpeak = "0"

            isMoneyDetected = false
            repeatSpeakHandler.removeCallbacks(repeatSpeakRunnable)

            if (noMoneyDetectedTimestamp == 0L) {
                noMoneyDetectedTimestamp = SystemClock.uptimeMillis()
            }
        }
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        runOnUiThread {
            binding.inferenceTime.text = "${inferenceTime}ms"
            binding.overlay.apply {
                setResults(boundingBoxes)
                invalidate()
            }

            val totalAmount = boundingBoxes.sumOf { box ->
                box.clsName.toDoubleOrNull() ?: 0.0
            }

            binding.totalCountLabel.text = "$totalAmount dinars"

            val newAmountToSpeak = totalAmount.toString()
            val currentTime = SystemClock.uptimeMillis()

            // If detected amount changed, update and restart repeating speech
            if (newAmountToSpeak != totalAmountToSpeak) {
                totalAmountToSpeak = newAmountToSpeak
                lastSpeakTime = 0L // reset cooldown so speech happens immediately

                // Restart repeating speech
                isMoneyDetected = newAmountToSpeak != "0"
                repeatSpeakHandler.removeCallbacks(repeatSpeakRunnable)
                if (isMoneyDetected) {
                    repeatSpeakHandler.post(repeatSpeakRunnable)
                }
            }

            if (boundingBoxes.isNotEmpty()) {
                noMoneyDetectedTimestamp = SystemClock.uptimeMillis()
                if (!isFlashOn) {
                    toggleFlash(true)
                }
            }
        }
    }

    companion object {
        private const val TAG = "MoneyCountingActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
