// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/ArViewModel.kt
package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import com.hereliesaz.graffitixr.nativebridge.depth.StereoDepthProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import javax.inject.Inject

@HiltViewModel
class ArViewModel @Inject constructor(
    private val slamManager: SlamManager,
    private val stereoProvider: StereoDepthProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(ArUiState())
    val uiState = _uiState.asStateFlow()

    private val _isCameraInUseByAr = MutableStateFlow(false)
    /**
     * True if ARCore is currently holding or initializing the camera hardware.
     * CameraX consumers should wait for this to be false before attempting to bind.
     */
    val isCameraInUseByAr = _isCameraInUseByAr.asStateFlow()

    private var renderer: ArRenderer? = null
    private var session: Session? = null

    private var isActivityResumed = false
    private var isInArMode = false
    private var isSessionResumed = false
    
    private val sessionMutex = Mutex()
    private var isDestroying = false

    // ==================== Session Lifecycle Management ====================

    fun onActivityResumed() {
        viewModelScope.launch {
            sessionMutex.withLock {
                isActivityResumed = true
                updateSessionStateLocked()
            }
        }
    }

    fun onActivityPaused() {
        viewModelScope.launch {
            sessionMutex.withLock {
                isActivityResumed = false
                updateSessionStateLocked()
            }
        }
    }

    fun setArMode(enabled: Boolean, context: Context) {
        viewModelScope.launch {
            sessionMutex.withLock {
                Log.d("ArViewModel", "setArMode: $enabled (current: $isInArMode)")
                if (isInArMode == enabled) return@withLock
                isInArMode = enabled

                if (enabled) {
                    isDestroying = false
                    initArSessionLocked(context)
                    updateSessionStateLocked()
                } else {
                    isDestroying = true
                    performFullCleanupLocked()
                    isDestroying = false
                }
            }
        }
    }

    private fun updateSessionStateLocked() {
        val shouldBeRunning = isActivityResumed && isInArMode && !isDestroying
        if (shouldBeRunning && !isSessionResumed) {
            resumeArSessionInternal()
        } else if (!shouldBeRunning && isSessionResumed) {
            pauseArSessionInternal()
        }
    }

    private fun initArSessionLocked(context: Context) {
        if (session == null) {
            try {
                _isCameraInUseByAr.value = true
                session = Session(context)
                val config = session!!.config
                config.depthMode = Config.DepthMode.AUTOMATIC
                config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                
                // Flashlight support (best effort)
                try {
                    val flashlightModeClass = Class.forName("com.google.ar.core.Config\$FlashlightMode")
                    val offMode = flashlightModeClass.getField("OFF").get(null)
                    val method = config.javaClass.getMethod("setFlashlightMode", flashlightModeClass)
                    method.invoke(config, offMode)
                } catch (e: Exception) {}
                
                session!!.configure(config)
                renderer?.attachSession(session)
                Log.d("ArViewModel", "AR Session Initialized")

                // Fix 4: Load SuperPoint model asynchronously on first session creation.
                viewModelScope.launch(Dispatchers.IO) {
                    val loaded = slamManager.loadSuperPoint(context.assets)
                    Log.d("ArViewModel", "SuperPoint: ${if (loaded) "ready" else "ORB fallback"}")
                }
            } catch (e: Throwable) {
                _isCameraInUseByAr.value = false
                Log.e("ArViewModel", "Failed to initialize AR session", e)
            }
        }
    }

    private fun resumeArSessionInternal() {
        val s = session ?: return
        try {
            s.resume()
            isSessionResumed = true
            _isCameraInUseByAr.value = true
            slamManager.setRelocEnabled(true)
            Log.d("ArViewModel", "AR Session Resumed")
        } catch (e: CameraNotAvailableException) {
            Log.e("ArViewModel", "Camera not available on resume", e)
        } catch (e: Exception) {
            Log.e("ArViewModel", "Unexpected error during AR resume", e)
        }
    }

    private fun pauseArSessionInternal() {
        if (!isSessionResumed) return
        isSessionResumed = false
        try {
            // Unlink renderer BEFORE pausing to avoid "Session is paused" errors from the render thread
            renderer?.attachSession(null)
            
            session?.pause()
            slamManager.setRelocEnabled(false)
            Log.d("ArViewModel", "AR Session Paused")
        } catch (e: Exception) {
            Log.e("ArViewModel", "Failed to pause AR session", e)
        }
    }

    private suspend fun performFullCleanupLocked() {
        Log.d("ArViewModel", "Starting full cleanup")
        
        // 1. Unlink renderer to stop update() calls
        renderer?.attachSession(null)
        
        // 2. Pause if needed
        if (isSessionResumed) {
            try {
                session?.pause()
            } catch (e: Exception) {
                Log.e("ArViewModel", "Pause failed during cleanup", e)
            }
            isSessionResumed = false
        }
        
        // 3. Wait for HAL to stabilize and stop repeating requests.
        // This helps avoid "Function not implemented (-38)" cancelRequest errors.
        delay(500)
        
        // 4. Close session and release camera
        val s = session
        session = null
        s?.let {
            try {
                it.close()
                Log.d("ArViewModel", "AR Session Closed")
            } catch (e: Exception) {
                Log.e("ArViewModel", "Close failed during cleanup", e)
            }
        }
        
        _isCameraInUseByAr.value = false
        slamManager.setRelocEnabled(false)
        Log.d("ArViewModel", "AR Session Cleanup Complete")
    }

    /**
     * Force immediate cleanup when Activity is destroyed.
     */
    fun destroyArSession() {
        // We use Main.immediate to ensure this starts before the process is killed,
        // but performFullCleanupLocked still needs to happen sequentially.
        viewModelScope.launch(Dispatchers.Main.immediate) {
            sessionMutex.withLock {
                if (session == null) return@withLock
                isInArMode = false
                isDestroying = true
                performFullCleanupLocked()
                isDestroying = false
            }
        }
    }

    // ==================== UI State & Capture ====================

    fun setTrackingState(isTracking: Boolean) {
        if (_uiState.value.isScanning == isTracking) return
        _uiState.update { it.copy(isScanning = isTracking) }
    }

    fun setTempCapture(bitmap: Bitmap) {
        _uiState.update { it.copy(tempCaptureBitmap = bitmap) }
    }

    fun onCaptureConsumed() {
        _uiState.update { it.copy(tempCaptureBitmap = null) }
    }

    fun setUnwarpPoints(points: List<Offset>) {
        _uiState.update { it.copy(unwarpPoints = points) }
    }

    fun setActiveUnwarpPoint(index: Int) {
        _uiState.update { it.copy(activeUnwarpPointIndex = index) }
    }

    fun setMagnifierPosition(position: Offset) {
        _uiState.update { it.copy(magnifierPosition = position) }
    }

    fun updateMaskPath(path: androidx.compose.ui.graphics.Path?) {
        _uiState.update { it.copy(maskPath = path) }
    }

    fun requestCapture() {
        _uiState.update { it.copy(isCaptureRequested = true) }
    }

    fun onCaptureRequestHandled() {
        _uiState.update { it.copy(isCaptureRequested = false) }
    }

    fun captureKeyframe() {
        // Native engine handles this
    }

    fun toggleFlashlight() {
        val isOn = !_uiState.value.isFlashlightOn
        _uiState.update { it.copy(isFlashlightOn = isOn) }
        renderer?.updateFlashlight(isOn)
    }

    fun ensureEngineInitialized() {
        slamManager.ensureInitialized()
    }

    fun onCameraFrameForStereo(buffer: ByteBuffer, width: Int, height: Int) {
        stereoProvider.submitFrame(buffer, width, height)
    }

    fun updateLightLevel(level: Float) {
        if (kotlin.math.abs(_uiState.value.lightLevel - level) < 0.01f) return
        _uiState.update { it.copy(lightLevel = level) }
    }

    fun attachSessionToRenderer(arRenderer: ArRenderer?) {
        this.renderer = arRenderer
        // If we already have a session, attach it now
        if (arRenderer != null && session != null) {
            arRenderer.attachSession(session)
        }
    }
}
