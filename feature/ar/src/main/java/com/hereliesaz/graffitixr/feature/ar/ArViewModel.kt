package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import com.hereliesaz.graffitixr.nativebridge.depth.StereoDepthProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.ByteBuffer
import javax.inject.Inject

@HiltViewModel
class ArViewModel @Inject constructor(
    private val slamManager: SlamManager,
    private val stereoProvider: StereoDepthProvider   // Issue 1: temporal stereo depth
) : ViewModel() {

    private val _uiState = MutableStateFlow(ArUiState())
    val uiState = _uiState.asStateFlow()

    private var renderer: ArRenderer? = null
    private var session: Session? = null

    private var isActivityResumed = false
    private var isInArMode = false
    private var isSessionResumed = false

    // ==================== Session Lifecycle Management ====================

    /**
     * Notifies the ViewModel that the parent Activity is resumed.
     */
    fun onActivityResumed() {
        isActivityResumed = true
        updateSessionState()
    }

    /**
     * Notifies the ViewModel that the parent Activity is paused.
     */
    fun onActivityPaused() {
        isActivityResumed = false
        updateSessionState()
    }

    /**
     * Notifies the ViewModel that the UI has entered or exited AR mode.
     * This is the primary trigger for creating/destroying the AR session.
     */
    fun setArMode(enabled: Boolean, context: Context) {
        if (isInArMode == enabled) return
        isInArMode = enabled

        if (enabled) {
            initArSession(context)
        }
        // Don't destroy session immediately, let pause handle it
        // to prevent race conditions when switching to CameraX.
        updateSessionState()
    }

    /**
     * Centralized logic to decide if the AR session should be running.
     * The session should only be active if the UI is in AR mode AND the Activity is in the foreground.
     */
    private fun updateSessionState() {
        val shouldBeRunning = isActivityResumed && isInArMode
        if (shouldBeRunning && !isSessionResumed) {
            resumeArSession()
        } else if (!shouldBeRunning && isSessionResumed) {
            pauseArSession()
        }
    }

    private fun initArSession(context: Context) {
        if (session == null) {
            try {
                session = Session(context)
                val config = session!!.config
                config.depthMode = Config.DepthMode.AUTOMATIC
                config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                session!!.configure(config)

                // If renderer is already attached, attach the session to it
                renderer?.attachSession(session)
                Log.d("ArViewModel", "AR Session Initialized")
            } catch (e: Throwable) {
                Log.e("ArViewModel", "Failed to initialize AR session", e)
            }
        }
    }

    fun attachSessionToRenderer(arRenderer: ArRenderer?) {
        this.renderer = arRenderer
        // If session is already initialized, attach it to the renderer
        renderer?.attachSession(session)
    }

    private fun resumeArSession() {
        val s = session ?: return
        try {
            s.resume()
            isSessionResumed = true
            slamManager.setRelocEnabled(true)  // Issue 3: re-enable reloc on AR resume
            Log.d("ArViewModel", "AR Session Resumed")
        } catch (e: CameraNotAvailableException) {
            Log.e("ArViewModel", "Camera not available on resume", e)
        } catch (e: IllegalStateException) {
            // Session already resumed — safe to ignore
            Log.w("ArViewModel", "resumeArSession called on already-resumed session", e)
        }
    }

    private fun pauseArSession() {
        if (!isSessionResumed) return // Avoid redundant pauses
        isSessionResumed = false
        session?.pause()
        slamManager.setRelocEnabled(false)  // Issue 3: stop waking reloc thread off-mode
        Log.d("ArViewModel", "AR Session Paused")
    }

    fun destroyArSession() {
        session?.close()
        session = null
        renderer?.attachSession(null)
        Log.d("ArViewModel", "AR Session Destroyed")
    }

    // ======================================================================

    /**
     * Updates the tracking state from ARCore. No-ops if unchanged to avoid frame-rate churn.
     */
    fun setTrackingState(isTracking: Boolean) {
        if (_uiState.value.isScanning == isTracking) return
        _uiState.update { it.copy(isScanning = isTracking) }
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

    fun captureKeyframe() {
        // Keyframe capture is handled by the native engine
        // This triggers the SLAM engine to save the current state
    }

    // ==================== Flashlight ====================

    fun toggleFlashlight() {
        val isOn = !_uiState.value.isFlashlightOn
        _uiState.update { it.copy(isFlashlightOn = isOn) }
        renderer?.updateFlashlight(isOn)
    }

    // ==================== SLAM Engine Delegation ====================

    /**
     * Ensures the native SLAM engine is initialized.
     * Called by ArRenderer on surface creation.
     */
    fun ensureEngineInitialized() {
        slamManager.ensureInitialized()
    }

    // ==================== Stereo Depth (Temporal) ====================

    /**
     * Receives a Y-plane frame from CameraX (OVERLAY mode) for temporal stereo depth.
     * Consecutive frames are treated as left/right pair and fed to the SLAM engine.
     */
    fun onCameraFrameForStereo(buffer: ByteBuffer, width: Int, height: Int) {
        stereoProvider.submitFrame(buffer, width, height)
    }
}
