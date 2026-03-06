package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.util.EnumSet
import javax.inject.Inject

@HiltViewModel
class ArViewModel @Inject constructor(
    private val slamManager: SlamManager,
    private val stereoProvider: StereoDepthProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(ArUiState())
    val uiState: StateFlow<ArUiState> = _uiState.asStateFlow()

    private var session: Session? = null
    private var renderer: ArRenderer? = null
    
    private val _isCameraInUseByAr = MutableStateFlow(false)
    val isCameraInUseByAr = _isCameraInUseByAr.asStateFlow()

    private var isActivityResumed = false
    private var isInArMode = false
    private var isSessionResumed = false
    private var isDestroying = false

    private val sessionMutex = Mutex()

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
                val newSession = Session(context)
                val filter = CameraConfigFilter(newSession).apply {
                    targetFps = EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30)
                }
                val supportedConfigs = newSession.getSupportedCameraConfigs(filter)
                if (supportedConfigs.isNotEmpty()) {
                    newSession.cameraConfig = supportedConfigs[0]
                }

                val config = Config(newSession).apply {
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    depthMode = Config.DepthMode.AUTOMATIC
                    focusMode = Config.FocusMode.AUTO
                }
                
                try {
                    val flashlightModeClass = Class.forName("com.google.ar.core.Config\$FlashlightMode")
                    val offMode = flashlightModeClass.getField("OFF").get(null)
                    val method = config.javaClass.getMethod("setFlashlightMode", flashlightModeClass)
                    method.invoke(config, offMode)
                } catch (e: Exception) {}

                newSession.configure(config)
                session = newSession
                renderer?.attachSession(newSession)
                
                viewModelScope.launch(Dispatchers.IO) {
                    slamManager.loadSuperPoint(context.assets)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _isCameraInUseByAr.value = false
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
            renderer?.attachSession(s)
        } catch (e: CameraNotAvailableException) {
            e.printStackTrace()
        } catch (e: IllegalStateException) {
            e.printStackTrace()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun pauseArSessionInternal() {
        if (!isSessionResumed) return
        isSessionResumed = false
        try {
            renderer?.attachSession(null)
            session?.pause()
            slamManager.setRelocEnabled(false)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun performFullCleanupLocked() {
        renderer?.attachSession(null)
        if (isSessionResumed) {
            try { session?.pause() } catch (e: Exception) {}
            isSessionResumed = false
        }
        delay(150)
        val s = session
        session = null
        s?.let {
            try { it.close() } catch (e: Exception) {}
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

    fun attachSessionToRenderer(arRenderer: ArRenderer?) {
        this.renderer = arRenderer
        if (arRenderer != null && session != null && isSessionResumed) {
            arRenderer.attachSession(session)
        }
    }

    fun setTrackingState(isTracking: Boolean, splatCount: Int) {
        _uiState.update { it.copy(
            isScanning = isTracking,
            splatCount = splatCount
        )}
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

    fun setTempCapture(bitmap: Bitmap?) {
        _uiState.update { it.copy(tempCaptureBitmap = bitmap) }
    }

    fun requestCapture() {
        _uiState.update { it.copy(isCaptureRequested = true) }
    }

    fun onCaptureRequestHandled() {
        _uiState.update { it.copy(isCaptureRequested = false) }
    }

    fun setActiveUnwarpPoint(index: Int) {
        _uiState.update { it.copy(activeUnwarpPointIndex = index) }
    }

    fun setMagnifierPosition(offset: Offset) {
        _uiState.update { it.copy(magnifierPosition = offset) }
    }

    fun updateMaskPath(path: Path) {
        _uiState.update { it.copy(maskPath = path) }
    }

    fun captureKeyframe() {
        // Handled via native bridge directly if implemented
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
        stereoProvider.submitFrame(buffer, width, height, System.currentTimeMillis())
    }

    fun onCameraFrameForStereo(image: ImageProxy) {
        stereoProvider.submitFrame(image.planes[0].buffer, image.width, image.height, System.currentTimeMillis())
        image.close()
    }

    fun updateLightLevel(level: Float) {
        _uiState.update { it.copy(lightLevel = level) }
    }
}
