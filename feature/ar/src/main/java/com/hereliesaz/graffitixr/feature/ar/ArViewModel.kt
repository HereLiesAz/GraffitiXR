package com.hereliesaz.graffitixr.feature.ar

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.nativebridge.depth.StereoDepthProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class ArViewModel @Inject constructor(
    private val stereoDepthProvider: StereoDepthProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(ArUiState())
    val uiState: StateFlow<ArUiState> = _uiState.asStateFlow()

    init {
        // Log capability status for debugging
        if (stereoDepthProvider.isSupported()) {
            println("ArViewModel: Stereo Depth is SUPPORTED on this device.")
        } else {
            println("ArViewModel: Stereo Depth is NOT supported.")
        }
    }

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

    /**
     * Triggers the capture of a SLAM keyframe.
     * Sets a temporary path/ID which the View observes to call the Native Bridge.
     */
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
}