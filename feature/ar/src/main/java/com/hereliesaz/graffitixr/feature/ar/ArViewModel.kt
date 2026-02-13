package com.hereliesaz.graffitixr.feature.ar

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import com.hereliesaz.graffitixr.common.model.ArUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * ViewModel for the AR feature.
 * Manages the [ArUiState], handles UI events related to AR (toggling features, capturing targets),
 * and bridges communication between the UI and the AR Renderer.
 */
@HiltViewModel
class ArViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(ArUiState())
    val uiState: StateFlow<ArUiState> = _uiState.asStateFlow()

    private val _newTargetImage = MutableSharedFlow<Pair<Bitmap?, String>>()
    /**
     * Emits a new target image to be added to the ARCore AugmentedImageDatabase at runtime.
     * The Pair contains the Bitmap and a unique name string.
     */
    val newTargetImage: SharedFlow<Pair<Bitmap?, String>> = _newTargetImage.asSharedFlow()

    /**
     * Toggles the visibility of the point cloud visualization.
     */
    fun togglePointCloud() {
        _uiState.update { it.copy(showPointCloud = !it.showPointCloud) }
    }

    /**
     * Toggles the device flashlight (torch mode).
     */
    fun toggleFlashlight() {
        _uiState.update { it.copy(isFlashlightOn = !it.isFlashlightOn) }
    }

    /**
     * Sets the temporary bitmap captured during the target creation flow (e.g., for the Rectify step).
     */
    fun setTempCapture(bitmap: Bitmap) {
        _uiState.update { it.copy(tempCaptureBitmap = bitmap) }
    }

    /**
     * Called when a target image has been fully processed and saved.
     * Updates the list of available targets in the UI state.
     *
     * @param bitmap The final target image.
     * @param uri The URI where the image is stored.
     */
    fun onFrameCaptured(bitmap: Bitmap, uri: Uri) {
        _uiState.update {
            it.copy(
                capturedTargetUris = it.capturedTargetUris + uri,
                capturedTargetImages = it.capturedTargetImages + bitmap
            )
        }
        // Signal AR renderer to update database if needed
        // viewModelScope.launch { _newTargetImage.emit(bitmap to "target_${System.currentTimeMillis()}") }
    }

    /**
     * Updates the target detection status.
     *
     * @param isDetected True if an Augmented Image is currently being tracked.
     */
    fun onTargetDetected(isDetected: Boolean) {
        _uiState.update { it.copy(isTargetDetected = isDetected) }
    }

    /**
     * Updates debug metrics from the AR engine.
     *
     * @param state The current tracking state description.
     * @param planes Number of detected planes.
     * @param points Number of points in the point cloud.
     */
    fun updateTrackingState(state: String, planes: Int, points: Int) {
        _uiState.update {
            it.copy(
                trackingState = state,
                planeCount = planes,
                pointCloudCount = points
            )
        }
    }
}
