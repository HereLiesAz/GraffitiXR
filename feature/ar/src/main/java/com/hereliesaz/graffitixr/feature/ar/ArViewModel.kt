package com.hereliesaz.graffitixr.feature.ar

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.nativebridge.depth.StereoDepthProvider
import com.hereliesaz.graffitixr.feature.ar.computervision.TeleologicalTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class ArViewModel @Inject constructor(
    private val stereoDepthProvider: StereoDepthProvider,
    private val teleologicalTracker: TeleologicalTracker,
    private val projectRepository: ProjectRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ArUiState())
    val uiState = _uiState.asStateFlow()

    fun toggleFlashlight() {
        _uiState.update { it.copy(isFlashlightOn = !it.isFlashlightOn) }
    }

    fun togglePointCloud() {
        _uiState.update { it.copy(showPointCloud = !it.showPointCloud) }
    }

    fun setTempCapture(bitmap: Bitmap?) {
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

    fun onCaptureConsumed() {
        _uiState.update { it.copy(tempCaptureBitmap = null) }
    }

    fun requestCapture() {}
    fun updateUnwarpPoints(points: List<Offset>) {}
    fun setActiveUnwarpPointIndex(index: Int?) {}
    fun setMagnifierPosition(position: Offset?) {}
    fun setMaskPath(path: androidx.compose.ui.graphics.Path?) {}

    fun captureKeyframe() {
        _uiState.update { it.copy(pendingKeyframePath = "/tmp/keyframe_${System.currentTimeMillis()}") }
    }

    fun onKeyframeCaptured() {
        _uiState.update { it.copy(pendingKeyframePath = null) }
    }

    fun processTeleologicalFrame(image: android.media.Image): org.opencv.core.Mat {
        return teleologicalTracker.processTeleologicalFrame(image)
    }

    fun processTeleologicalFrame(bitmap: Bitmap, vararg params: Any): org.opencv.core.Mat {
        return teleologicalTracker.processTeleologicalFrame(bitmap)
    }
}