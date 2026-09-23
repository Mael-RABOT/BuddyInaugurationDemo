package com.example.buddydemo

import android.os.Bundle
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.lifecycleScope
import com.bfr.buddy.speech.shared.ITTSCallback
import com.bfr.buddy.ui.shared.LabialExpression
import com.bfr.buddy.usb.shared.IUsbCommadRsp
import com.bfr.buddy.vision.shared.Detections
import com.bfr.buddysdk.BuddyCompatActivity
import com.bfr.buddysdk.BuddySDK
import com.example.buddydemo.ui.theme.BuddyDemoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : BuddyCompatActivity() {
    data class SpeechOption(
        val text: String,
        val weight: Float
    )

    private val speechOptions = listOf(
        SpeechOption("Bienvenue chez HEpitech!", 0.5f),
        SpeechOption("Bonjour et bienvenue!", 0.3f),
        SpeechOption("Ravi de vous rencontrer!", 0.2f)
    )

    private fun randomSpeech(): String {
        val totalWeight = speechOptions.sumOf { it.weight.toDouble() }
        var random = Math.random() * totalWeight

        for (option in speechOptions) {
            random -= option.weight
            if (random <= 0) {
                return option.text
            }
        }
        return speechOptions.last().text
    }

    companion object {
        private const val TAG = "BuddyDebug"
        private const val DETECTION_THRESHOLD = 0.7f
        private const val SPEECH_COOLDOWN_MS = 8000L
        private const val ROTATION_SPEED_DEG_S = 90.0f
    }

    private var isSdkReady = false
    private var areWheelsEnabled = false
    private var isMoving = false
    private var isSpeaking = false
    private var lastSpokenTime = 0L

    private var visionJob: Job? = null
    private var touchSensorJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: Activity started")

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                BuddyDemoTheme {
                    RobotDisplayScreen()
                }
            }
        }

        setContentView(composeView)
    }

    override fun onSDKReady() {
        super.onSDKReady()
        Log.i(TAG, "onSDKReady: Connected to Buddy core service.")
        try {
            BuddySDK.Speech.loadReadSpeaker()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading ReadSpeaker: ${e.message}")
        }
        isSdkReady = true

        // Automatically activate wheels
        enableWheels()

        // Background services always running
        startPersonDetectionLoop()
        startTouchSensorLoop()
    }

    private fun startTouchSensorLoop() {
        touchSensorJob?.cancel()
        touchSensorJob = lifecycleScope.launch(Dispatchers.Default) {
            while (isActive) {
                if (isSdkReady && areWheelsEnabled && !isMoving) {
                    try {
                        val headSensors = BuddySDK.Sensors.HeadTouchSensors()
                        val isHeadTouched = headSensors.Top().isTouched ||
                                headSensors.Left().isTouched ||
                                headSensors.Right().isTouched

                        if (isHeadTouched) {
                            Log.i(TAG, "Head sensor touch detected! Triggering 360 rotation...")
                            launch(Dispatchers.Main) {
                                rotate360()
                            }
                            delay(1000) // Debounce touch events
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading head touch sensors: ${e.message}")
                    }
                }
                delay(100)
            }
        }
    }

    private fun startPersonDetectionLoop() {
        visionJob?.cancel()
        visionJob = lifecycleScope.launch(Dispatchers.Default) {
            Log.d(TAG, "Vision loop started")
            while (isActive) {
                if (isSdkReady) {
                    try {
                        val detections: Detections? = BuddySDK.Vision.detectPerson(DETECTION_THRESHOLD)
                        var validPersonCount = 0

                        if (detections != null) {
                            val leftList = detections.leftPos
                            val rightList = detections.rightPos
                            val topList = detections.topPos
                            val bottomList = detections.bottomPos

                            val totalEntries = leftList?.size ?: 0
                            if (totalEntries > 0 && rightList != null && topList != null && bottomList != null) {
                                for (i in 0 until totalEntries) {
                                    val left = leftList.getOrNull(i) ?: 0f
                                    val right = rightList.getOrNull(i) ?: 0f
                                    val top = topList.getOrNull(i) ?: 0f
                                    val bottom = bottomList.getOrNull(i) ?: 0f

                                    val width = right - left
                                    val height = bottom - top

                                    if (width > 0.05f && height > 0.05f && right <= 1.0f && bottom <= 1.0f) {
                                        validPersonCount++
                                    }
                                }
                            }
                        }

                        if (validPersonCount > 0) {
                            val currentTime = System.currentTimeMillis()
                            if (!isSpeaking && (currentTime - lastSpokenTime > SPEECH_COOLDOWN_MS)) {
                                Log.i(TAG, "Person detected ($validPersonCount). Speaking...")
                                lastSpokenTime = currentTime
                                launch(Dispatchers.Main) {
                                    speak()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error during detectPerson(): ${e.message}")
                    }
                }
                delay(400)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun enableWheels() {
        if (!isSdkReady) return

        BuddySDK.USB.enableWheels(1, 1, object : IUsbCommadRsp.Stub() {
            override fun onSuccess(s: String?) {
                Log.i(TAG, "enableWheels onSuccess: $s")
                if (s == "OK") {
                    areWheelsEnabled = true
                }
            }

            override fun onFailed(p0: String?) {
                Log.e(TAG, "enableWheels onFailed: $p0")
                areWheelsEnabled = false
            }
        })
    }

    private fun rotate360() {
        if (!areWheelsEnabled || isMoving) return

        isMoving = true
        BuddySDK.USB.rotateBuddy(ROTATION_SPEED_DEG_S, 360.0f, object : IUsbCommadRsp.Stub() {
            override fun onSuccess(s: String?) {
                Log.i(TAG, "rotateBuddy onSuccess: $s")
                if (s == "WHEEL_MOVE_FINISHED") {
                    isMoving = false
                }
            }

            override fun onFailed(p0: String?) {
                Log.e(TAG, "rotateBuddy onFailed: $p0")
                isMoving = false
            }
        })
    }

    private fun speak() {
        if (!isSdkReady || isSpeaking) return

        isSpeaking = true
        BuddySDK.Speech.startSpeaking(
            randomSpeech(),
            LabialExpression.SPEAK_HAPPY,
            object : ITTSCallback.Stub() {
                override fun onSuccess(utteranceId: String?) {
                    Log.i(TAG, "TTS finished successfully.")
                    isSpeaking = false
                }

                override fun onError(errorMsg: String?) {
                    Log.e(TAG, "TTS failed: $errorMsg")
                    isSpeaking = false
                }

                override fun onPause() {}
                override fun onResume() {}
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        visionJob?.cancel()
        touchSensorJob?.cancel()

        if (isSdkReady) {
            try {
                BuddySDK.Speech.stopSpeaking()
                BuddySDK.USB.emergencyStopMotors(object : IUsbCommadRsp.Stub() {
                    override fun onSuccess(s: String?) {}
                    override fun onFailed(p0: String?) {}
                })
            } catch (e: Exception) {
                Log.e(TAG, "Cleanup exception: ${e.message}")
            }
        }
    }
}

@Composable
fun RobotDisplayScreen() {
    Image(
        painter = painterResource(id = R.drawable.bg_buddy),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize()
    )
}