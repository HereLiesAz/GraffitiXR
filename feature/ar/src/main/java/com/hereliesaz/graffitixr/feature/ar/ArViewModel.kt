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
import kotlinx.coroutines.withContext
import timber.log.Timber
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
    val isCameraInUseByAr = _isCameraInUseByAr.asStateFlow()

    private var renderer: ArRenderer? = null
    private var session: Session? = null

    private var isActivityResumed = false
    private var isInArMode = false
    private var requestedArMode = false
    private var isSessionResumed = false

    private val sessionMutex = Mutex()
    private var isDestroying = false

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
        requestedArMode = enabled
        viewModelScope.launch {
            if (enabled) {
                // Give CameraX time to release the camera hardware asynchronously
                delay(600)
            }
            sessionMutex.withLock {
                // Ensure we don't start AR if the user quickly navigated away during the delay
                if (requestedArMode != enabled) return@withLock
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

    private suspend fun updateSessionStateLocked() {
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

                try {
                    val flashlightModeClass = Class.forName("com.google.ar.core.Config\$FlashlightMode")
                    val offMode = flashlightModeClass.getField("OFF").get(null)
                    val method = config.javaClass.getMethod("setFlashlightMode", flashlightModeClass)
                    method.invoke(config, offMode)
                } catch (e: Exception) {}

                session!!.configure(config)
                renderer?.attachSession(session)

                viewModelScope.launch(Dispatchers.IO) {
                    slamManager.loadSuperPoint(context.assets)
                }
            } catch (e: Throwable) {
                Timber.e(e, "Failed to create ARCore Session")
                _isCameraInUseByAr.value = false
                session = null
            }
        }
    }

    private suspend fun resumeArSessionInternal() {
        val s = session ?: return
        var retries = 5
        while (retries > 0) {
            // Offload camera hardware binding to IO thread to prevent skipped frames
            val success = withContext(Dispatchers.IO) {
                try {
                    s.resume()
                    true
                } catch (e: CameraNotAvailableException) {
                    false
                } catch (e: IllegalStateException) {
                    true // Already resumed
                } catch (e: Exception) {
                    Timber.e(e, "Failed to resume ARCore session")
                    false
                }
            }

            if (success) {
                isSessionResumed = true
                _isCameraInUseByAr.value = true
                slamManager.setRelocEnabled(true)
                renderer?.attachSession(s)
                return
            } else {
                retries--
                if (retries > 0) {
                    delay(300)
                } else {
                    Timber.e(Exception("Timeout"), "Camera not available to resume ARCore session after retries")
                    _isCameraInUseByAr.value = false
                }
            }
        }
    }

    private suspend fun pauseArSessionInternal() {
        if (!isSessionResumed) return
        isSessionResumed = false
        val s = session

        // Detaching from renderer locks the GL thread and ensures it stops calling session.update()
        // before we pause, preventing the MediaPipe RET_CHECK crash.
        renderer?.attachSession(null)
        slamManager.setRelocEnabled(false)

        // Offload hardware release to IO thread
        withContext(Dispatchers.IO) {
            try {
                s?.pause()
            } catch (e: Exception) {
                Timber.e(e, "Failed to pause ARCore session")
            }
        }
    }

    private suspend fun performFullCleanupLocked() {
        val s = session
        renderer?.attachSession(null)
        session = null
        isSessionResumed = false

        // Offload hardware teardown to IO thread to prevent main thread freezing
        withContext(Dispatchers.IO) {
            try { s?.pause() } catch (e: Exception) {}
            try { s?.close() } catch (e: Exception) {}
        }

        _isCameraInUseByAr.value = false
        slamManager.setRelocEnabled(false)
    }

    fun destroyArSession() {
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

    fun setTrackingState(isTracking: Boolean, splatCount: Int) {
        _uiState.update { it.copy(isScanning = isTracking, splatCount = splatCount) }
    }

    fun setTempCapture(bitmap: Bitmap) {
        _uiState.update { it.copy(tempCaptureBitmap = bitmap) }
    }

    fun onTargetCaptured(bitmap: Bitmap, depthBuffer: ByteBuffer?, width: Int, height: Int, intrinsics: FloatArray?) {
        _uiState.update {
            it.copy(
                tempCaptureBitmap = bitmap,
                targetDepthBuffer = depthBuffer,
                targetDepthWidth = width,
                targetDepthHeight = height,
                targetIntrinsics = intrinsics,
                isCaptureRequested = false
            )
        }
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

    fun captureKeyframe() {}

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
        if (arRenderer != null && session != null && isSessionResumed) {
            arRenderer.attachSession(session)
        }
    }
}