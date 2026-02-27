package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraManager
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.ByteBuffer
import javax.inject.Inject

/**
 * Manages spatial state and native bridge coordination for AR features.
 */
@HiltViewModel
class ArViewModel @Inject constructor(
    private val slamManager: SlamManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(ArUiState())
    val uiState = _uiState.asStateFlow()

    /**
     * Feeds camera frame data to the SLAM engine.
     */
    fun onFrameAvailable(buffer: ByteBuffer, width: Int, height: Int) {
        slamManager.feedMonocularData(buffer, width, height)
    }

    /**
     * Captures a spatial keyframe and increments the local undo stack count.
     */
    fun captureKeyframe() {
        val timestamp = System.currentTimeMillis()
        val keyframesDir = java.io.File(context.filesDir, "keyframes").apply { mkdirs() }
        val outputPath = java.io.File(keyframesDir, "keyframe_$timestamp.gxrm").absolutePath
        val success = slamManager.saveKeyframe(timestamp, outputPath)
        if (success) {
            _uiState.update { it.copy(pendingKeyframePath = outputPath) }
        }
    }

    /**
     * Toggles the hardware flashlight state via Android Camera2 API.
     */
    fun toggleFlashlight() {
        val newState = !_uiState.value.isFlashlightOn
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return
            cameraManager.setTorchMode(cameraId, newState)
            _uiState.update { it.copy(isFlashlightOn = newState) }
        } catch (e: Exception) {
            // Camera unavailable or torch not supported
        }
    }

    /**
     * Signals the native engine to prepare for session start.
     */
    fun initEngine() {
        slamManager.initialize()
    }

    fun startScanning() {
        _uiState.update { it.copy(isScanning = true) }
    }

    fun stopScanning() {
        _uiState.update { it.copy(isScanning = false) }
    }

    fun updateTrackingState(state: String, pointCount: Int) {
        _uiState.update { it.copy(trackingState = state, pointCloudCount = pointCount) }
    }

    fun setTempCapture(bitmap: Bitmap) {
        _uiState.update { it.copy(tempCaptureBitmap = bitmap) }
    }

    fun setUnwarpPoints(points: List<Offset>) {
        _uiState.update { it.copy(unwarpPoints = points) }
    }
}
