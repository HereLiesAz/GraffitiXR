package com.hereliesaz.graffitixr

import android.app.Application
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.xr.runtime.math.Pose
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
     * Handles the selection of a new overlay image from the device's storage.
     *
     * @param uri The URI of the selected image.
     */
    fun onSelectImage(uri: Uri?) {
        _uiState.update { it.copy(imageUri = uri) }
    }

    /**
     * Handles the selection of a new background image, switching to static image mode.
     *
     * @param uri The URI of the selected background image.
     */
    fun onSelectBackgroundImage(uri: Uri?) {
        _uiState.update {
            it.copy(
                backgroundImageUri = uri,
                editorMode = EditorMode.STATIC_IMAGE,
                // Reset AR state when switching modes
                markerPoses = emptyList(),
                hitTestPose = null,
                // Initialize sticker corners for the new background
                stickerCorners = listOf(
                    Offset(100f, 100f),
                    Offset(300f, 100f),
                    Offset(300f, 300f),
                    Offset(100f, 300f)
                )
            )
        }
    }

    /**
     * Initiates the background removal process for the selected image.
     * This is a long-running operation that runs on a background thread.
     */
    fun onRemoveBg() {
        uiState.value.imageUri?.let { uri ->
            viewModelScope.launch {
                _uiState.update { it.copy(isProcessing = true) }
                val result = withContext(Dispatchers.IO) {
                    ImageProcessor.removeBackground(getApplication(), uri)
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
     * Clears the work in the current mode.
     */
    fun onClear() {
        _uiState.update {
            when (it.editorMode) {
                EditorMode.AR -> it.copy(markerPoses = emptyList())
                EditorMode.STATIC_IMAGE -> it.copy(
                    stickerCorners = listOf(
                        Offset(100f, 100f),
                        Offset(300f, 100f),
                        Offset(300f, 300f),
                        Offset(100f, 300f)
                    )
                )
                else -> it
            }
        }
    }

    /**
     * Adds a new marker at the current hit test position in AR mode.
     */
    fun onAddMarker() {
        uiState.value.hitTestPose?.let { pose ->
            if (uiState.value.markerPoses.size < 4) {
                _uiState.update {
                    it.copy(markerPoses = it.markerPoses + pose)
                }
            }
        }
    }

    /**
     * Updates the current hit test pose from the AR scene.
     *
     * @param pose The new pose from the hit test, or null if no valid surface is hit.
     */
    fun onHitTestResult(pose: Pose?) {
        _uiState.update { it.copy(hitTestPose = pose) }
    }

    /**
     * Updates the positions of the sticker corners in static image mode.
     * @param corners The new list of corner offsets.
     */
    fun onStickerCornersChange(corners: List<Offset>) {
        if (_uiState.value.editorMode == EditorMode.STATIC_IMAGE) {
            _uiState.update { it.copy(stickerCorners = corners) }
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
     * Clears the snackbar message from the UI state after it has been shown.
     */
    fun onSnackbarMessageShown() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    /**
     * Toggles the visibility of the settings screen.
     */
    fun onSettingsClicked(show: Boolean) {
        _uiState.update { it.copy(showSettings = show) }
    }

    /**
     * Updates the hue value for UI color customization.
     *
     * @param value The new hue value.
     */
    fun onHueChange(value: Float) {
        _uiState.update { it.copy(hue = value) }
    }

    /**
     * Updates the lightness value for UI color customization.
     *
     * @param value The new lightness value.
     */
    fun onLightnessChange(value: Float) {
        _uiState.update { it.copy(lightness = value) }
    }
}