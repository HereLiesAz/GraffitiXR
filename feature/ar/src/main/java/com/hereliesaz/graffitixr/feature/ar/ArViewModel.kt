package com.hereliesaz.graffitixr.feature.ar

import androidx.lifecycle.ViewModel
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import dagger.hilt.android.lifecycle.HiltViewModel
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
    private val slamManager: SlamManager
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
        val success = slamManager.saveKeyframe(timestamp)
        if (success) {
            _uiState.update { it.copy(undoCount = it.undoCount + 1) }
        }
    }

    /**
     * Toggles the hardware flashlight state via the native bridge.
     */
    fun toggleFlashlight() {
        slamManager.toggleFlashlight()
    }

    /**
     * Signals the native engine to prepare for session start.
     */
    fun initEngine() {
        slamManager.initialize()
    }

    /**
     * Updates the UI state to show or hide gesture-specific overlays.
     */
    fun setGestureInProgress(inProgress: Boolean) {
        _uiState.update { currentState ->
            // Ensuring the property is accessed on the correct type
            currentState.copy(gestureInProgress = inProgress)
        }
    }

    fun setTempCapture(bitmap: android.graphics.Bitmap) {
        _uiState.update { it.copy(tempCaptureBitmap = bitmap) }
    }

    fun setUnwarpPoints(points: List<androidx.compose.ui.geometry.Offset>) {
        _uiState.update { it.copy(unwarpPoints = points) }
    }
}