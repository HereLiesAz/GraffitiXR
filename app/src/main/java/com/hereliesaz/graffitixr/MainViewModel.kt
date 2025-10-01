package com.hereliesaz.graffitixr

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.xr.runtime.math.Pose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


/**
 * The central ViewModel for the GraffitiXR application, responsible for managing the UI state
 * and handling all business logic and user interactions.
 *
 * This class follows the MVVM architecture pattern. It exposes the application state via a
 * [StateFlow] and provides a suite of public functions that can be called from the UI
 * (Composables) to signal user events. All state modifications are centralized here and
 * are performed in an immutable fashion by creating a new copy of the [UiState].
 *
 * @param application The [Application] instance, which is required by [AndroidViewModel]
 * and used here to get a context for operations like background removal.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UiState())

    /**
     * The public, read-only [StateFlow] of the [UiState].
     *
     * UI components should collect this flow to observe state changes and recompose accordingly.
     * It provides a single source of truth for the entire application's UI.
     */
    val uiState = _uiState.asStateFlow()

    // A job to manage the AR guidance timer.
    private var arGuidanceJob: Job? = null

    /**
     * Updates the state with a new URI for the overlay image.
     * This is typically called after the user selects an image from their gallery.
     *
     * @param uri The [Uri] of the selected overlay image, or null to clear it.
     */
    fun onSelectImage(uri: Uri?) {
        _uiState.update { it.copy(imageUri = uri) }
    }

    /**
     * Updates the state with a new background image and switches the application to
     * [EditorMode.STATIC_IMAGE].
     *
     * This function also resets any AR-related state and initializes the sticker corners
     * to a default position for the new background.
     *
     * @param uri The [Uri] of the selected background image.
     */
    fun onSelectBackgroundImage(uri: Uri?) {
        // Cancel the AR guidance timer when switching out of AR mode.
        arGuidanceJob?.cancel()
        _uiState.update {
            it.copy(
                backgroundImageUri = uri,
                editorMode = EditorMode.STATIC_IMAGE,
                // Reset AR state when switching modes.
                markerPoses = emptyList(),
                hitTestPose = null,
                showARGuidance = false, // Hide guidance message
                // Initialize sticker corners to a default state for the new background.
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
     * Initiates the asynchronous background removal process for the current overlay image.
     *
     * This function launches a coroutine in the `viewModelScope`. It sets the `isProcessing`
     * state to true, then calls [ImageProcessor.removeBackground] on a background thread.
     * On success, it updates the `imageUri` with the URI of the processed image.
     * On failure, it logs the error and sets a user-facing snackbar message.
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
                }.onFailure { exception ->
                    Log.e("MainViewModel", "Background removal failed", exception)
                    _uiState.update { it.copy(snackbarMessage = "Failed to remove background. Please try a different image.") }
                }
                _uiState.update { it.copy(isProcessing = false) }
            }
        }
    }

    /**
     * Resets the current work in the active editor mode.
     * - In [EditorMode.AR], it clears the list of placed markers.
     * - In [EditorMode.STATIC_IMAGE], it resets the sticker corners to their default positions.
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
     * Adds a new AR marker to the scene if fewer than four markers have been placed.
     * The marker is placed at the current `hitTestPose`.
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
     * Updates the current AR hit test result from the underlying AR session.
     *
     * @param pose The latest [Pose] from the AR hit test, or null if no surface is detected.
     */
    fun onHitTestResult(pose: Pose?) {
        _uiState.update { it.copy(hitTestPose = pose) }

        if (pose == null) {
            // If no plane is detected and a timer isn't already running, start one.
            if (arGuidanceJob == null || arGuidanceJob?.isActive == false) {
                arGuidanceJob = viewModelScope.launch {
                    delay(5000L) // Wait for 5 seconds
                    _uiState.update { it.copy(showARGuidance = true) }
                }
            }
        } else {
            // If a plane is detected, cancel the timer and hide the guidance message.
            arGuidanceJob?.cancel()
            if (_uiState.value.showARGuidance) {
                _uiState.update { it.copy(showARGuidance = false) }
            }
        }
    }

    /**
     * Updates the positions of the four draggable corners in [EditorMode.STATIC_IMAGE].
     *
     * @param corners A list of four [Offset] points representing the new corner positions.
     */
    fun onStickerCornersChange(corners: List<Offset>) {
        if (_uiState.value.editorMode == EditorMode.STATIC_IMAGE) {
            _uiState.update { it.copy(stickerCorners = corners) }
        }
    }

    /**
     * Sets which adjustment slider (e.g., opacity, contrast) is currently active in the UI.
     *
     * @param sliderType The [SliderType] to show, or null to hide all sliders.
     */
    fun onSliderSelected(sliderType: SliderType?) {
        _uiState.update { it.copy(activeSlider = sliderType) }
    }

    /**
     * Updates the opacity value of the overlay image.
     * @param value The new opacity value, typically from 0.0f to 1.0f.
     */
    fun onOpacityChange(value: Float) {
        _uiState.update { it.copy(opacity = value) }
    }

    /**
     * Updates the contrast value of the overlay image.
     * @param value The new contrast value.
     */
    fun onContrastChange(value: Float) {
        _uiState.update { it.copy(contrast = value) }
    }

    /**
     * Updates the saturation value of the overlay image.
     * @param value The new saturation value.
     */
    fun onSaturationChange(value: Float) {
        _uiState.update { it.copy(saturation = value) }
    }

    /**
     * Updates the brightness value of the overlay image.
     * @param value The new brightness value.
     */
    fun onBrightnessChange(value: Float) {
        _uiState.update { it.copy(brightness = value) }
    }

    /**
     * Clears the snackbar message from the UI state.
     * This should be called after the snackbar has been displayed to the user.
     */
    fun onSnackbarMessageShown() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    /**
     * Toggles the visibility of the settings screen.
     * @param show True to show the settings screen, false to hide it.
     */
    fun onSettingsClicked(show: Boolean) {
        _uiState.update { it.copy(showSettings = show) }
    }

    /**
     * Updates the hue value for the app's dynamic UI theme color.
     * @param value The new hue value.
     */
    fun onHueChange(value: Float) {
        _uiState.update { it.copy(hue = value) }
    }

    /**
     * Updates the lightness value for the app's dynamic UI theme color.
     * @param value The new lightness value.
     */
    fun onLightnessChange(value: Float) {
        _uiState.update { it.copy(lightness = value) }
    }

    /**
     * Changes the active editor mode and performs necessary state cleanup.
     *
     * @param mode The new [EditorMode] to switch to.
     */
    fun onEditorModeChange(mode: EditorMode) {
        // Cancel the AR guidance job if we are leaving AR mode.
        if (mode != EditorMode.AR) {
            arGuidanceJob?.cancel()
        }
        _uiState.update {
            it.copy(
                editorMode = mode,
                // Reset AR state if not in AR mode.
                showARGuidance = if (mode != EditorMode.AR) false else it.showARGuidance
            )
        }
    }
}