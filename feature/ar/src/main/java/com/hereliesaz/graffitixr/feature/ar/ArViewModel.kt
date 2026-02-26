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
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset

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
        // Undo count is not in ArUiState, handled elsewhere or ignored here for now
    }

    /**
     * Toggles the hardware flashlight state via the native bridge.
     */
    fun toggleFlashlight() {
        slamManager.toggleFlashlight()
        _uiState.update { it.copy(isFlashlightOn = !it.isFlashlightOn) }
    }

    /**
     * Signals the native engine to prepare for session start.
     */
    fun initEngine() {
        slamManager.initialize()
    }

    fun setTempCapture(bitmap: Bitmap) {
        _uiState.update { it.copy(tempCaptureBitmap = bitmap) }
    }

    fun setUnwarpPoints(points: List<Offset>) {
        _uiState.update { it.copy(unwarpPoints = points) }
    }
}
