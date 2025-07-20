package com.adamtz.ai_sight_android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.os.SystemClock
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.adamtz.ai_sight_android.databinding.ActivityDoorDetectionBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.adamtz.ai_sight_android.Constants.DOOR_MODEL_PATH
import com.adamtz.ai_sight_android.Constants.DOOR_LABELS_PATH
import android.media.AudioAttributes
import android.media.SoundPool

class DoorDetectionActivity : AppCompatActivity(), Detector.DetectorListener {

    private lateinit var binding: ActivityDoorDetectionBinding
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var detector: Detector
    private lateinit var cameraExecutor: ExecutorService

    // Sound variables
    private lateinit var soundPool: SoundPool
    private var searchingSoundId = 0
    private var popSoundId = 0
    private var leftSoundId = 0
    private var rightSoundId = 0
    private var doorAheadSoundId = 0
    private var isSearchingSoundPlaying = false
    private var lastDoorDetected = false
    private var lastDirection = ""

    private var soundsLoaded = false
    private var searchingStreamId = 0

    // Triple tap detection variables
    private var tapCount = 0
    private var lastTapTime = 0L
    private val tripleTapTimeout = 1000L // 1 second window for triple tap

    companion object {
        private const val TAG = "DoorDetectionActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDoorDetectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize SoundPool with proper AudioAttributes
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(audioAttributes)
            .build()

        // Load sounds and listen for completion
        soundPool.setOnLoadCompleteListener { _, _, status ->
            if (status == 0) {
                soundsLoaded = true
                Log.d(TAG, "SoundPool sounds loaded successfully")
            } else {
                Log.e(TAG, "SoundPool sound loading failed")
            }
        }

        searchingSoundId = soundPool.load(this, R.raw.searching, 1)
        popSoundId = soundPool.load(this, R.raw.pop, 1)
        leftSoundId = soundPool.load(this, R.raw.left, 1)
        rightSoundId = soundPool.load(this, R.raw.right, 1)
        doorAheadSoundId = soundPool.load(this, R.raw.door_ahead, 1)

        detector = Detector(baseContext, DOOR_MODEL_PATH, DOOR_LABELS_PATH, this)
        detector.setup()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Triple tap listener on root view to exit
        binding.root.setOnClickListener {
            val currentTime = SystemClock.uptimeMillis()
            if (currentTime - lastTapTime > tripleTapTimeout) {
                tapCount = 1
            } else {
                tapCount++
            }
            lastTapTime = currentTime

            if (tapCount == 3) {
                val intent = Intent(this, HomeActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                startActivity(intent)
                finish()
                tapCount = 0 // Reset tap count after triple tap detected
            }
        }

    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")
        val rotation = binding.viewFinder.display.rotation

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
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
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                android.widget.Toast.makeText(this, "Permissions not granted by the user.", android.widget.Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        detector.clear()
        cameraExecutor.shutdown()
        soundPool.release()
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        runOnUiThread {
            binding.inferenceTime.text = "${inferenceTime}ms"
            binding.overlay.apply {
                setResults(boundingBoxes)
                invalidate()
            }

            if (boundingBoxes.isEmpty()) {
                if (!isSearchingSoundPlaying && soundsLoaded) {
                    searchingStreamId = soundPool.play(searchingSoundId, 1f, 1f, 1, -1, 1f)
                    isSearchingSoundPlaying = true
                }
                lastDoorDetected = false
                lastDirection = ""
            } else {
                if (isSearchingSoundPlaying) {
                    soundPool.stop(searchingStreamId)
                    isSearchingSoundPlaying = false
                }
                if (!lastDoorDetected && soundsLoaded) {
                    soundPool.play(popSoundId, 1f, 1f, 1, 0, 1f)
                }
                lastDoorDetected = true

                val doorBox = boundingBoxes[0]
                val viewWidth = binding.viewFinder.width.toFloat()
                val centerX = ((doorBox.x1 + doorBox.x2) / 2f) * viewWidth

                val leftThreshold = viewWidth / 3
                val rightThreshold = 2 * viewWidth / 3

                val direction = when {
                    centerX < leftThreshold -> "left"
                    centerX > rightThreshold -> "right"
                    else -> "center"
                }

                if (direction != lastDirection && soundsLoaded) {
                    when (direction) {
                        "left" -> soundPool.play(leftSoundId, 1f, 1f, 1, 0, 1f)
                        "right" -> soundPool.play(rightSoundId, 1f, 1f, 1, 0, 1f)
                        "center" -> soundPool.play(doorAheadSoundId, 1f, 1f, 1, 0, 1f)
                    }
                    lastDirection = direction
                }
            }
        }
    }

    override fun onEmptyDetect() {
        runOnUiThread {
            binding.overlay.setResults(emptyList())
            binding.overlay.invalidate()
        }
    }
}
