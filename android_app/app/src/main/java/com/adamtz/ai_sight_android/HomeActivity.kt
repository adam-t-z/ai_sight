package com.adamtz.ai_sight_android

import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import com.adamtz.ai_sight_android.databinding.ActivityHomeBinding
import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator


class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private var mediaPlayer: MediaPlayer? = null

    // Enum representing the modes to improve readability
    enum class Mode(val audioResId: Int, val displayName: String) {
        MONEY(R.raw.money_mode, "Money Mode"),
        DOOR(R.raw.door_mode, "Door Mode")
    }

    private var currentMode = Mode.MONEY

    private val LONG_PRESS_TIMEOUT = 600L
    private var isLongPress = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Play initial mode audio (Money mode)
        playModeAudio(currentMode.audioResId)
        updateCurrentModeText()  // Update the displayed mode text

        binding.root.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isLongPress = false
                    handler.postDelayed({
                        isLongPress = true
                        handleLongPress()
                    }, LONG_PRESS_TIMEOUT)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacksAndMessages(null)
                    if (!isLongPress) {
                        handleSingleTap()
                        binding.root.performClick()
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun handleSingleTap() {
        // Switch mode
        currentMode = if (currentMode == Mode.MONEY) Mode.DOOR else Mode.MONEY
        playModeAudio(currentMode.audioResId)
        updateCurrentModeText()  // Update the UI with the new mode text
    }

    private fun handleLongPress() {
        vibrateShort()
        val intent = when (currentMode) {
            Mode.MONEY -> Intent(this, MoneyCountingActivity::class.java)
            Mode.DOOR -> Intent(this, DoorDetectionActivity::class.java)
        }

        handler.postDelayed({
            startActivity(intent)
        }, 700)
    }

    private fun playModeAudio(audioResId: Int) {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer.create(this, audioResId)
        mediaPlayer?.start()
    }

    private fun updateCurrentModeText() {
        // Update the current mode text in the UI
        binding.currentModeText.text = "Current Mode: ${currentMode.displayName}"
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        handler.removeCallbacksAndMessages(null)
    }

    private fun vibrateShort() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (vibrator.hasVibrator()) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                vibrator.vibrate(100)
            }
        }
    }
}


