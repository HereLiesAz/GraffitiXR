package com.hereliesaz.graffitixr.feature.ar

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.common.model.GpsData
import com.hereliesaz.graffitixr.common.model.SensorData
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel responsible for managing the AR session state and UI.
 * Handles user interactions related to AR features like flashlight control,
 * target capture workflow, and tracking status updates.
 */
@HiltViewModel
class ArViewModel @Inject constructor(
    private val projectRepository: ProjectRepository
) : ViewModel() {

    // Internal mutable state
    private val _uiState = MutableStateFlow(ArUiState())

    /**
     * Public immutable state flow observed by the UI.
     */
    val uiState: StateFlow<ArUiState> = _uiState.asStateFlow()

    /**
     * Toggles the device flashlight state.
     * Note: The actual hardware control happens in the ArView/Renderer based on this state.
     */
    fun toggleFlashlight() {
        _uiState.update { it.copy(isFlashlightOn = !it.isFlashlightOn) }
    }

    /**
     * Toggles the visibility of the point cloud visualization.
     */
    fun togglePointCloud() {
        _uiState.update { it.copy(showPointCloud = !it.showPointCloud) }
    }

    /**
     * Stores a temporary bitmap captured from the AR view.
     * Used during the "Capture" step of target creation.
     *
     * @param bitmap The raw captured frame.
     */
    fun setTempCapture(bitmap: Bitmap) {
        _uiState.update { it.copy(tempCaptureBitmap = bitmap) }
    }

    /**
     * Finalizes the target creation process.
     * Clears the temporary capture and marks the target as "Detected" (simulating a successful add).
     *
     * @param bitmap The final processed (unwarped) target image.
     * @param uri The URI where the image was saved.
     */
    fun onFrameCaptured(bitmap: Bitmap, uri: Uri) {
        _uiState.update {
            it.copy(
                tempCaptureBitmap = null,
                isTargetDetected = true,
                capturedTargetUris = it.capturedTargetUris + uri,
                capturedTargetImages = it.capturedTargetImages + bitmap
            )
        }
    }

    /**
     * Resets the capture state, discarding any temporary images.
     */
    fun resetCapture() {
        _uiState.update { it.copy(tempCaptureBitmap = null) }
    }

    /**
     * Updates the AR tracking status and metrics.
     * Called by the AR Renderer/View when tracking state changes.
     *
     * @param state The current tracking state description (e.g. "Tracking", "Paused").
     * @param planeCount The number of detected planes.
     * @param pointCloudCount The number of feature points being tracked.
     */
    fun updateTrackingState(state: String, planeCount: Int, pointCloudCount: Int) {
        _uiState.update {
            it.copy(
                trackingState = state,
                planeCount = planeCount,
                pointCloudCount = pointCloudCount,
                // Infer detected state from string for backward compatibility logic
                isTargetDetected = state == "Tracking" || state == "TRACKING"
            )
        }
    }

    /**
     * Manually sets the target detected state.
     * Used by tests or simulated events.
     */
    fun onTargetDetected(detected: Boolean) {
        _uiState.update { it.copy(isTargetDetected = detected) }
    }

    /**
     * Updates the current GPS location data.
     */
    fun updateLocation(data: GpsData) {
        _uiState.update { it.copy(gpsData = data) }
        viewModelScope.launch {
            projectRepository.updateProject { it.copy(gpsData = data) }
        }
    }

    /**
     * Updates the current device orientation data.
     */
    fun updateSensors(data: SensorData) {
        _uiState.update { it.copy(sensorData = data) }
        viewModelScope.launch {
            projectRepository.updateProject { it.copy(sensorData = data) }
        }
    }
}