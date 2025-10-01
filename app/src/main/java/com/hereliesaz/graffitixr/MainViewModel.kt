package com.hereliesaz.graffitixr

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.xr.arcore.Anchor
import androidx.xr.arcore.HitResult
import androidx.xr.arcore.Plane
import androidx.xr.arcore.anchor.AnchorCreateSuccess
import androidx.xr.arcore.anchor.create
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
     * Resets the mural, returning to placement mode.
     */
    fun onResetMural() {
        // Clear all graffiti by removing their anchors
        _uiState.value.graffiti.forEach { it.detach() }
        _uiState.update {
            it.copy(
                graffiti = emptyList(),
                placementMode = true
            )
        }
    }

    /**
     * Handles a tap in the AR scene, creating an anchor if a plane is hit.
     */
    fun onARTap(hitResults: List<HitResult>) {
        if (!_uiState.value.placementMode) return

        val planeHit = hitResults.firstOrNull { it.trackable is Plane }
        if (planeHit != null) {
            viewModelScope.launch {
                val anchorResult = Anchor.create(planeHit.pose)
                if (anchorResult is AnchorCreateSuccess) {
                    _uiState.update {
                        it.copy(
                            graffiti = it.graffiti + anchorResult.anchor,
                            placementMode = false
                        )
                    }
                }
            }
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