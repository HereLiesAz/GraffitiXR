package com.hereliesaz.graffitixr.feature.ar

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.google.ar.core.Session
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
    var session: Session? = null
        private set
    var isSessionResumed = false
        private set

    /**
     * Initializes the ARCore session.
     */
    fun initArSession(context: android.content.Context) {
        if (session == null) {
            try {
                session = Session(context)
                // Configure the session (e.g., depth API)
                val config = session!!.config
                config.depthMode = com.google.ar.core.Config.DepthMode.AUTOMATIC
                config.updateMode = com.google.ar.core.Config.UpdateMode.LATEST_CAMERA_IMAGE
                session!!.configure(config)
                
                // If renderer is already attached, attach the session to it
                renderer?.attachSession(session)
            } catch (e: Exception) {
                Log.e("ArViewModel", "Failed to initialize AR session", e)
            }
        }
    }

    fun attachSessionToRenderer(arRenderer: ArRenderer?) {
        this.renderer = arRenderer
        // If session is already initialized, attach it to the renderer
        renderer?.attachSession(session)
    }

    fun resumeArSession() {
        val s = session ?: return
        try {
            s.resume()
            isSessionResumed = true
        } catch (e: com.google.ar.core.exceptions.CameraNotAvailableException) {
            Log.e("ArViewModel", "Camera not available on resume", e)
        } catch (e: IllegalStateException) {
            // Session already resumed — safe to ignore
            Log.w("ArViewModel", "resumeArSession called on already-resumed session", e)
        }
    }

    fun pauseArSession() {
        isSessionResumed = false
        session?.pause()
    }

    fun destroyArSession() {
        session?.close()
        session = null
        renderer?.attachSession(null)
    }

    /**
     * Updates the tracking state from ARCore.
     */
    fun setTrackingState(isTracking: Boolean) {
        _uiState.update {
            it.copy(
                isScanning = isTracking
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
    fun updateMaskPath(path: androidx.compose.ui.graphics.Path?) {
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
