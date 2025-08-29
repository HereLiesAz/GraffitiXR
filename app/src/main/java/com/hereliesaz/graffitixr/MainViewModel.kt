package com.hereliesaz.graffitixr

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ar.core.Pose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


/**
 * The ViewModel for the main screen of the GraffitiXR application.
 * It holds the UI state and handles all user interactions and business logic.
 *
 * @param application The application instance.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UiState())
    /**
     * The state flow of the UI, observed by the composables.
     */
    val uiState = _uiState.asStateFlow()

    /**
     * Handles the selection of a new image from the device's storage.
     *
     * @param uri The URI of the selected image.
     */
    fun onSelectImage(uri: Uri?) {
        _uiState.update { it.copy(imageUri = uri) }
    }

    /**
     * Initiates the background removal process for the selected image.
     * This is a long-running operation that runs on a background thread.
     */
    fun onRemoveBgClicked() {
        uiState.value.imageUri?.let { uri ->
            viewModelScope.launch {
                _uiState.update { it.copy(isProcessing = true) }
                val result = withContext(Dispatchers.IO) {
                    removeBackground(getApplication(), uri)
                }
                result.onSuccess { newUri ->
                    _uiState.update { it.copy(imageUri = newUri) }
                }.onFailure {
                    _uiState.update { it.copy(snackbarMessage = "Background removal failed.") }
                }
                _uiState.update { it.copy(isProcessing = false) }
            }
        }
    }

    /**
     * Locks the mural's pose in the AR scene based on the current camera pose.
     * This exits placement mode.
     */
    fun onLockMural() {
        uiState.value.cameraPose?.let {
            // TODO: This is a placeholder for locking the mural.
            // The final implementation should use marker detection to determine the pose.
            val translation = floatArrayOf(0f, 0f, -2f)
            val rotation = floatArrayOf(0f, 0f, 0f, 1f)
            val lockedPose = it.compose(Pose(translation, rotation))
            _uiState.update { state ->
                state.copy(
                    lockedPose = lockedPose,
                    placementMode = false
                )
            }
        }
    }

    /**
     * Resets the mural, returning to placement mode.
     */
    fun onResetMural() {
        _uiState.update {
            it.copy(
                placementMode = true,
                lockedPose = null
            )
        }
    }

    /**
     * Sets the active slider in the UI.
     *
     * @param sliderType The type of slider to activate, or null to hide it.
     */
    fun onSliderSelected(sliderType: SliderType?) {
        _uiState.update { it.copy(activeSlider = sliderType) }
    }

    /**
     * Updates the opacity of the image.
     *
     * @param value The new opacity value.
     */
    fun onOpacityChange(value: Float) {
        _uiState.update { it.copy(opacity = value) }
    }

    /**
     * Updates the contrast of the image.
     *
     * @param value The new contrast value.
     */
    fun onContrastChange(value: Float) {
        _uiState.update { it.copy(contrast = value) }
    }

    /**
     * Updates the saturation of the image.
     *
     * @param value The new saturation value.
     */
    fun onSaturationChange(value: Float) {
        _uiState.update { it.copy(saturation = value) }
    }

    /**
     * Updates the brightness of the image.
     *
     * @param value The new brightness value.
     */
    fun onBrightnessChange(value: Float) {
        _uiState.update { it.copy(brightness = value) }
    }

    /**
     * Updates the camera pose in the UI state.
     *
     * @param pose The new camera pose.
     */
    fun onCameraPoseChange(pose: Pose?) {
        _uiState.update { it.copy(cameraPose = pose) }
    }

    /**
     * Clears the snackbar message from the UI state after it has been shown.
     */
    fun onSnackbarMessageShown() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }
}
