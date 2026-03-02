package com.hereliesaz.graffitixr.feature.ar

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import com.google.ar.core.TrackingState
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class ArViewModel @Inject constructor(
    private val slamManager: SlamManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ArUiState())
    val uiState = _uiState.asStateFlow()

    private var renderer: ArRenderer? = null
    var isSessionResumed = false
        private set

    fun attachSessionToRenderer(arRenderer: ArRenderer) {
        this.renderer = arRenderer
    }

    fun resumeArSession() {
        isSessionResumed = true
        renderer?.session?.resume()
    }

    fun pauseArSession() {
        isSessionResumed = false
        renderer?.session?.pause()
    }

    /**
     * Updates the tracking state from ARCore.
     * Converts the ARCore TrackingState enum to a display string.
     */
    fun updateTrackingState(state: TrackingState, pointCount: Int) {
        val stateString = when (state) {
            TrackingState.TRACKING -> "Tracking"
            TrackingState.PAUSED -> "Paused"
            TrackingState.STOPPED -> "Stopped"
        }
        _uiState.update {
            it.copy(
                trackingState = stateString,
                pointCloudCount = pointCount,
                isScanning = state == TrackingState.TRACKING
            )
        }
    }

    // ==================== Capture Workflow ====================

    /**
     * Stores a captured bitmap for the target creation workflow.
     */
    fun setTempCapture(bitmap: Bitmap) {
        _uiState.update { it.copy(tempCaptureBitmap = bitmap) }
    }

    /**
     * Clears the temporary capture after it has been processed.
     */
    fun onCaptureConsumed() {
        _uiState.update { it.copy(tempCaptureBitmap = null) }
    }

    /**
     * Sets the corner points for perspective unwarp.
     */
    fun setUnwarpPoints(points: List<Offset>) {
        _uiState.update { it.copy(unwarpPoints = points) }
    }

    /**
     * Sets the active unwarp point being dragged.
     */
    fun setActiveUnwarpPoint(index: Int) {
        _uiState.update { it.copy(activeUnwarpPointIndex = index) }
    }

    /**
     * Updates the magnifier position during point adjustment.
     */
    fun setMagnifierPosition(position: Offset) {
        _uiState.update { it.copy(magnifierPosition = position) }
    }

    /**
     * Updates the mask path for target refinement.
     */
    fun updateMaskPath(path: androidx.compose.ui.graphics.Path) {
        _uiState.update { it.copy(maskPath = path) }
    }

    /**
     * Requests a capture from the camera.
     */
    fun requestCapture() {
        _uiState.update { it.copy(isCaptureRequested = true) }
    }

    /**
     * Clears the capture request flag after handling.
     */
    fun onCaptureRequestHandled() {
        _uiState.update { it.copy(isCaptureRequested = false) }
    }

    // ==================== Scanning Controls ====================

    fun startScanning() {
        _uiState.update { it.copy(isScanning = true) }
    }

    fun stopScanning() {
        _uiState.update { it.copy(isScanning = false) }
    }

    fun captureKeyframe() {
        // Keyframe capture is handled by the native engine
        // This triggers the SLAM engine to save the current state
    }

    // ==================== Flashlight ====================

    fun toggleFlashlight() {
        _uiState.update { it.copy(isFlashlightOn = !it.isFlashlightOn) }
    }

    // ==================== SLAM Engine Delegation ====================

    /**
     * Ensures the native SLAM engine is initialized.
     * Called by ArRenderer on surface creation.
     */
    fun ensureEngineInitialized() {
        slamManager.ensureInitialized()
    }
}
