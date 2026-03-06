// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/ArViewModel.kt
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
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.EnumSet
import javax.inject.Inject

@HiltViewModel
class ArViewModel @Inject constructor(
    private val slamManager: SlamManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ArUiState())
    val uiState: StateFlow<ArUiState> = _uiState.asStateFlow()

    private var arSession: Session? = null
    private var arRenderer: ArRenderer? = null

    fun setArMode(isActive: Boolean, context: Context) {
        if (isActive) {
            if (arSession == null) {
                try {
                    arSession = Session(context).apply {
                        val filter = CameraConfigFilter(this).apply {
                            targetFps = EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30)
                        }
                        val supportedConfigs = getSupportedCameraConfigs(filter)
                        if (supportedConfigs.isNotEmpty()) {
                            cameraConfig = supportedConfigs[0]
                        }

                        val config = Config(this).apply {
                            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                            depthMode = Config.DepthMode.AUTOMATIC
                            focusMode = Config.FocusMode.AUTO
                        }
                        configure(config)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    return
                }
            }
            arSession?.resume()
        } else {
            arSession?.pause()
        }
    }

    fun attachSessionToRenderer(renderer: ArRenderer) {
        arRenderer = renderer
        arSession?.let { renderer.setSession(it) }
    }

    fun onActivityResumed() {
        arSession?.resume()
    }

    fun onActivityPaused() {
        arSession?.pause()
    }

    fun destroyArSession() {
        val dyingSession = arSession
        arSession = null

        arRenderer?.destroy()
        arRenderer = null

        // Banish the closure to the IO thread so the MediaPipe graph
        // doesn't deadlock the Main Thread waiting for the GL context to drop.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dyingSession?.pause()
                delay(150) // Yield to allow internal C++ threads to acknowledge the pause
                dyingSession?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun setTrackingState(isTracking: Boolean, splatCount: Int) {
        _uiState.update { it.copy(
            isScanning = isTracking,
            splatCount = splatCount
        )}
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

    fun setActiveUnwarpPoint(index: Int) {
        _uiState.update { it.copy(activeUnwarpPointIndex = index) }
    }

    fun setMagnifierPosition(offset: Offset) {
        _uiState.update { it.copy(magnifierPosition = offset) }
    }

    fun updateMaskPath(path: Path) {
        _uiState.update { it.copy(maskPath = path) }
    }

    fun updateLightLevel(level: Float) {
        _uiState.update { it.copy(lightLevel = level) }
    }

    fun toggleFlashlight() {
        _uiState.update { it.copy(isFlashlightOn = !it.isFlashlightOn) }
    }

    fun captureKeyframe() {
        // Handled via native bridge directly if implemented
    }

    fun onTargetCaptured(bmp: Bitmap?, depth: FloatArray?, w: Int, h: Int, intrinsics: FloatArray?) {
        _uiState.update { it.copy(
            isCaptureRequested = false,
            tempCaptureBitmap = bmp
        )}
    }

    fun onCameraFrameForStereo(image: ImageProxy) {
        image.close()
    }
}