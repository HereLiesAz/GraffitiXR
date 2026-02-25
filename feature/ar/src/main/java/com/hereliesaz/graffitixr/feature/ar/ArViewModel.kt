package com.hereliesaz.graffitixr.feature.ar

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.feature.ar.computervision.TeleologicalTracker
import com.hereliesaz.graffitixr.nativebridge.depth.StereoDepthProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ArViewModel @Inject constructor(
    private val stereoDepthProvider: StereoDepthProvider,
    private val teleologicalTracker: TeleologicalTracker,
    private val projectRepository: ProjectRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ArUiState())
    val uiState: StateFlow<ArUiState> = _uiState.asStateFlow()

    fun setTempCapture(bitmap: Bitmap) {
        _uiState.update { it.copy(tempCaptureBitmap = bitmap) }
    }

    fun onFrameCaptured(bitmap: Bitmap, uri: Uri) {
        _uiState.update {
            it.copy(
                tempCaptureBitmap = bitmap,
                pendingKeyframePath = uri.path
            )
        }
    }

    fun captureKeyframe() {
        val timestamp = System.currentTimeMillis()
        _uiState.update { it.copy(pendingKeyframePath = "keyframe_$timestamp") }
    }

    fun onKeyframeCaptured() {
        _uiState.update { it.copy(pendingKeyframePath = null) }
    }

    fun toggleFlashlight() {
        _uiState.update { it.copy(isFlashlightOn = !it.isFlashlightOn) }
    }

    fun togglePointCloud() {
        _uiState.update { it.copy(showPointCloud = !it.showPointCloud) }
    }

    // --- Teleological Pipeline ---

    /**
     * Called periodically by the ArView image analyzer.
     * Triggers the OpenCV solvePnP algorithm to correct AR drift.
     */
    fun processTeleologicalFrame(bitmap: Bitmap, viewMatrix: FloatArray) {
        viewModelScope.launch {
            val project = projectRepository.currentProject.value ?: return@launch
            val fingerprint = project.fingerprint ?: return@launch

            // Basic FOV assumptions for standard mobile cameras.
            // In a production scenario, these should be pulled from CameraCharacteristics.
            val width = bitmap.width.toFloat()
            val height = bitmap.height.toFloat()
            val fx = width * 0.8f
            val fy = height * 0.8f
            val cx = width / 2f
            val cy = height / 2f
            val intrinsics = floatArrayOf(fx, fy, cx, cy)

            val success = teleologicalTracker.computeCorrection(bitmap, fingerprint, intrinsics, viewMatrix)
            if (success) {
                _uiState.update { it.copy(trackingState = "Locked (Teleological)") }
            }
        }
    }

    // Target Creation State Updates
    fun updateUnwarpPoints(points: List<androidx.compose.ui.geometry.Offset>) {
        _uiState.update { it.copy(unwarpPoints = points) }
    }

    fun setActiveUnwarpPointIndex(index: Int) {
        _uiState.update { it.copy(activeUnwarpPointIndex = index) }
    }

    fun setMagnifierPosition(position: androidx.compose.ui.geometry.Offset) {
        _uiState.update { it.copy(magnifierPosition = position) }
    }

    fun setMaskPath(path: androidx.compose.ui.graphics.Path?) {
        _uiState.update { it.copy(maskPath = path) }
    }

    fun requestCapture() {
        _uiState.update { it.copy(isCaptureRequested = true) }
    }

    fun onCaptureConsumed() {
        _uiState.update { it.copy(isCaptureRequested = false) }
    }
}