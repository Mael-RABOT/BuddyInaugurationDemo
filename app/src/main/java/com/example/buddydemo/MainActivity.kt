package com.example.buddydemo

import android.os.Bundle
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
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

        // Fallback for floating-point rounding
        return speechOptions.last().text
    }

    companion object {
        private const val TAG = "BuddyDebug"
        private const val DETECTION_THRESHOLD = 0.7f // 50% confidence threshold
        private const val SPEECH_COOLDOWN_MS = 8000L // 8s cooldown between greetings
    }

    private var isSdkReady by mutableStateOf(false)
    private var areWheelsEnabled by mutableStateOf(false)
    private var isMoving by mutableStateOf(false)
    private var isPersonDetected by mutableStateOf(false)

    private var isSpeaking = false
    private var lastSpokenTime = 0L
    private var visionJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: Activity started")

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                BuddyDemoTheme {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = Color.Transparent
                    ) { innerPadding ->
                        RobotControlScreen(
                            isSdkReady = isSdkReady,
                            areWheelsEnabled = areWheelsEnabled,
                            isMoving = isMoving,
                            isPersonDetected = isPersonDetected,
                            onSpeakClick = { speak() },
                            onEnableWheelsClick = { enableWheels() },
                            onMoveForwardClick = { moveForward() },
                            onRotate360Click = { rotate360() },
                            modifier = Modifier.padding(innerPadding)
                        )
                    }
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

        // Start person detection loop
        startPersonDetectionLoop()
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

                            // Check if all coordinate lists are populated and match in size
                            val totalEntries = leftList?.size ?: 0
                            if (totalEntries > 0 && rightList != null && topList != null && bottomList != null) {
                                for (i in 0 until totalEntries) {
                                    val left = leftList.getOrNull(i) ?: 0f
                                    val right = rightList.getOrNull(i) ?: 0f
                                    val top = topList.getOrNull(i) ?: 0f
                                    val bottom = bottomList.getOrNull(i) ?: 0f

                                    val width = right - left
                                    val height = bottom - top

                                    // Filter out false/empty/zero bounding boxes
                                    // Real detections occupy at least 5% width and 5% height of the frame
                                    if (width > 0.05f && height > 0.05f && right <= 1.0f && bottom <= 1.0f) {
                                        validPersonCount++
                                        Log.d(TAG, "Valid person #$i box: [L:$left, R:$right, T:$top, B:$bottom] (W:$width, H:$height)")
                                    }
                                }
                            }
                        }

                        // Update UI state on Main thread
                        val hasPerson = validPersonCount > 0
                        if (isPersonDetected != hasPerson) {
                            isPersonDetected = hasPerson
                        }

                        if (hasPerson) {
                            val currentTime = System.currentTimeMillis()
                            if (!isSpeaking && (currentTime - lastSpokenTime > SPEECH_COOLDOWN_MS)) {
                                Log.i(TAG, "Confirmed person in front ($validPersonCount detected)! Speaking...")
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
                delay(400) // Poll every 400ms to reduce CPU load on Buddy's tablet
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

    private fun moveForward() {
        if (!areWheelsEnabled || isMoving) return

        isMoving = true
        BuddySDK.USB.moveBuddy(0.2f, 0.4f, object : IUsbCommadRsp.Stub() {
            override fun onSuccess(s: String?) {
                Log.i(TAG, "moveBuddy onSuccess: $s")
                if (s == "WHEEL_MOVE_FINISHED") {
                    isMoving = false
                }
            }

            override fun onFailed(p0: String?) {
                Log.e(TAG, "moveBuddy onFailed: $p0")
                isMoving = false
            }
        })
    }

    private fun rotate360() {
        if (!areWheelsEnabled || isMoving) return

        isMoving = true
        BuddySDK.USB.rotateBuddy(50.0f, 360.0f, object : IUsbCommadRsp.Stub() {
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
fun RobotControlScreen(
    isSdkReady: Boolean,
    areWheelsEnabled: Boolean,
    isMoving: Boolean,
    isPersonDetected: Boolean,
    onSpeakClick: () -> Unit,
    onEnableWheelsClick: () -> Unit,
    onMoveForwardClick: () -> Unit,
    onRotate360Click: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize()
    ) {
        // Fullscreen Background Image
        Image(
            painter = painterResource(id = R.drawable.bg_buddy),
            contentDescription = "Background",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Overlay UI Controls
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = if (isPersonDetected) "Person in sight! \uD83D\uDC4B" else "Scanning for people...",
                color = Color.White
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onSpeakClick,
                enabled = isSdkReady
            ) {
                Text(text = if (isSdkReady) "Speak" else "Connecting to Buddy...")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onEnableWheelsClick,
                enabled = isSdkReady && !areWheelsEnabled
            ) {
                Text(text = if (areWheelsEnabled) "Wheels Enabled ✓" else "Enable Wheels")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onMoveForwardClick,
                enabled = areWheelsEnabled && !isMoving
            ) {
                Text(text = if (isMoving) "Moving..." else "Move Forward (40cm)")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onRotate360Click,
                enabled = areWheelsEnabled && !isMoving
            ) {
                Text(text = if (isMoving) "Rotating..." else "Rotate 360°")
            }
        }
    }
}