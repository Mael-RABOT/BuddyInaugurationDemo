package com.example.buddydemo

import android.os.Bundle
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import com.bfr.buddy.speech.shared.ITTSCallback
import com.bfr.buddy.ui.shared.LabialExpression
import com.bfr.buddy.usb.shared.IUsbCommadRsp
import com.bfr.buddysdk.BuddyCompatActivity
import com.bfr.buddysdk.BuddySDK
import com.example.buddydemo.ui.theme.BuddyDemoTheme

class MainActivity : BuddyCompatActivity() {

    companion object {
        private const val TAG = "BuddyDebug"
    }

    private var isSdkReady by mutableStateOf(false)
    private var areWheelsEnabled by mutableStateOf(false)
    private var isMoving by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: Activity started")

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                BuddyDemoTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = Color.Transparent
                    ) {
                        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                            RobotControlScreen(
                                isSdkReady = isSdkReady,
                                areWheelsEnabled = areWheelsEnabled,
                                isMoving = isMoving,
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
    }

    // Step 4 & 5: Enable wheel motors
    @Suppress("DEPRECATION")
    private fun enableWheels() {
        if (!isSdkReady) {
            Log.w(TAG, "Cannot enable wheels: SDK is not ready.")
            return
        }

        Log.d(TAG, "Enabling wheels (Left=1, Right=1)...")
        BuddySDK.USB.enableWheels(1, 1, object : IUsbCommadRsp.Stub() {
            override fun onSuccess(s: String?) {
                Log.i(TAG, "enableWheels onSuccess: $s")
                if (s == "OK") {
                    areWheelsEnabled = true
                }
            }

            override fun onFailed(p0: String?) {
                Log.e(TAG, "enableWheels onError: $p0")
                areWheelsEnabled = false
            }
        })
    }

    // Step 6, 7 & 8: Advance Buddy straight forward
    private fun moveForward() {
        if (!areWheelsEnabled || isMoving) return

        isMoving = true
        val speedMps = 0.2f       // Forward speed in m/s (0.05 to 0.7 m/s)
        val distanceMeters = 0.4f // Move 40 cm forward

        Log.d(TAG, "moveBuddy: Speed=$speedMps m/s, Distance=$distanceMeters m")
        BuddySDK.USB.moveBuddy(speedMps, distanceMeters, object : IUsbCommadRsp.Stub() {
            override fun onSuccess(s: String?) {
                Log.i(TAG, "moveBuddy onSuccess: $s")
                if (s == "WHEEL_MOVE_FINISHED" || s == "OK") {
                    if (s == "WHEEL_MOVE_FINISHED") {
                        isMoving = false
                    }
                }
            }

            override fun onFailed(p0: String?) {
                Log.e(TAG, "moveBuddy onError: $p0")
                isMoving = false
            }
        })
    }

    // Rotate 360 degrees on itself
    private fun rotate360() {
        if (!areWheelsEnabled || isMoving) return

        isMoving = true
        val rotationSpeed = 50.0f // deg/s (>0: counter-clockwise, <0: clockwise)
        val rotationAngle = 360.0f // full circle

        Log.d(TAG, "rotateBuddy: 360 deg at $rotationSpeed deg/s")
        BuddySDK.USB.rotateBuddy(rotationSpeed, rotationAngle, object : IUsbCommadRsp.Stub() {
            override fun onSuccess(s: String?) {
                Log.i(TAG, "rotateBuddy onSuccess: $s")
                if (s == "WHEEL_MOVE_FINISHED") {
                    isMoving = false
                }
            }

            override fun onFailed(p0: String?) {
                Log.e(TAG, "rotateBuddy onError: $p0")
                isMoving = false
            }
        })
    }

    private fun speak() {
        if (!isSdkReady) return

        BuddySDK.Speech.startSpeaking(
            "Bienvenue ché Epitech!",
            LabialExpression.SPEAK_HAPPY,
            object : ITTSCallback.Stub() {
                override fun onSuccess(utteranceId: String?) {
                    Log.i(TAG, "TTS finished successfully.")
                }

                override fun onError(errorMsg: String?) {
                    Log.e(TAG, "TTS failed: $errorMsg")
                }

                override fun onPause() {}
                override fun onResume() {}
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isSdkReady) {
            try {
                BuddySDK.Speech.stopSpeaking()
                BuddySDK.USB.emergencyStopMotors(object : IUsbCommadRsp.Stub() {
                    override fun onSuccess(s: String?) {}
                    override fun onFailed(p0: String?) {
                        Log.e(TAG, "Error in onDestroy: $p0")
                    }
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
    onSpeakClick: () -> Unit,
    onEnableWheelsClick: () -> Unit,
    onMoveForwardClick: () -> Unit,
    onRotate360Click: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Speech Action
            Button(
                onClick = onSpeakClick,
                enabled = isSdkReady
            ) {
                Text(text = if (isSdkReady) "Speak" else "Connecting to Buddy...")
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Step 2 & 3: Enable Wheels Button
            Button(
                onClick = onEnableWheelsClick,
                enabled = isSdkReady && !areWheelsEnabled
            ) {
                Text(text = if (areWheelsEnabled) "Wheels Enabled ✓" else "Enable Wheels")
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Step 6: Move Forward Button
            Button(
                onClick = onMoveForwardClick,
                enabled = areWheelsEnabled && !isMoving
            ) {
                Text(text = if (isMoving) "Moving..." else "Move Forward (40cm)")
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 360 Spin Button
            Button(
                onClick = onRotate360Click,
                enabled = areWheelsEnabled && !isMoving
            ) {
                Text(text = if (isMoving) "Rotating..." else "Rotate 360°")
            }
        }
    }
}