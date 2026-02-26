package com.hereliesaz.graffitixr.feature.ar

import android.graphics.Bitmap
import android.media.Image
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.common.DispatcherProvider
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.nativebridge.depth.StereoDepthProvider
import com.hereliesaz.graffitixr.feature.ar.computervision.TeleologicalTracker
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import javax.inject.Inject

@HiltViewModel
class ArViewModel @Inject constructor(
    private val slamManager: SlamManager,
    private val stereoDepthProvider: StereoDepthProvider,
    private val teleologicalTracker: TeleologicalTracker,
    private val projectRepository: ProjectRepository,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(ArUiState())
    val uiState = _uiState.asStateFlow()

    init {
        slamManager.initialize()
    }

    fun toggleFlashlight() {
        _uiState.update { it.copy(isFlashlightOn = !it.isFlashlightOn) }
    }

    fun togglePointCloud() {
        _uiState.update { it.copy(showPointCloud = !it.showPointCloud) }
    }

    /**
     * Processes pixel data on a background thread.
     * Receives a ByteArray copy to ensure the data persists after the ArImage is closed.
     */
    fun processTeleologicalFrame(yData: ByteArray, width: Int, height: Int) {
        viewModelScope.launch(dispatchers.default) {
            // 1. Feed to Native Engine
            val directBuffer = ByteBuffer.allocateDirect(yData.size)
            directBuffer.put(yData)
            directBuffer.position(0)
            slamManager.feedMonocularData(directBuffer, width, height)

            // 2. Feed to CV Tracker
            teleologicalTracker.processTeleologicalFrame(yData, width, height)
        }
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

    fun captureKeyframe() {
        val path = "/data/user/0/com.hereliesaz.graffitixr/cache/keyframe_${System.currentTimeMillis()}.bin"
        if (slamManager.saveKeyframe(path)) {
            _uiState.update { it.copy(pendingKeyframePath = path) }
        }
    }

    fun onKeyframeCaptured() {
        _uiState.update { it.copy(pendingKeyframePath = null) }
    }

    // Fallback for older UI components
    fun processTeleologicalFrame(bitmap: Bitmap): org.opencv.core.Mat {
        return teleologicalTracker.processTeleologicalFrame(bitmap)
    }
}