package com.hereliesaz.graffitixr

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The central ViewModel for the application, acting as the single source of truth for the UI state
 * and the handler for all user events.
 *
 * This class follows the MVVM architecture pattern. It holds the application's UI state in a
 * [StateFlow] and exposes public functions to modify that state in response to user interactions.
 * By extending [AndroidViewModel], it can safely access the application context to interact with
 * system services like [SharedPreferences].
 *
 * @param application The application instance, provided by the ViewModel factory. Used here to
 * access SharedPreferences for persisting user data (e.g., completed onboarding).
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val sharedPreferences = application.getSharedPreferences("GraffitiXR_prefs", Context.MODE_PRIVATE)

    /**
     * The private, mutable state flow that holds the current UI state.
     * All state modifications are posted to this flow.
     */
    private val _uiState: MutableStateFlow<UiState>

    /**
     * The public, immutable [StateFlow] that exposes the current UI state to the composables.
     * UI components should collect this flow to be notified of state changes.
     */
    val uiState: StateFlow<UiState>

    init {
        // When the ViewModel is created, load the set of completed onboarding modes from
        // SharedPreferences to ensure the onboarding dialog is not shown again for those modes.
        val completedModes = sharedPreferences.getStringSet("completed_onboarding", emptySet())
            ?.mapNotNull {
                try {
                    EditorMode.valueOf(it)
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
            ?.toSet() ?: emptySet()
        _uiState = MutableStateFlow(UiState(completedOnboardingModes = completedModes))
        uiState = _uiState.asStateFlow()
    }

    /**
     * Updates the UI state with the [Uri] of the image selected for the background.
     *
     * @param uri The content [Uri] of the selected background image.
     */
    fun onBackgroundImageSelected(uri: Uri) {
        _uiState.update { it.copy(backgroundImageUri = uri) }
    }

    /**
     * Updates the UI state with the [Uri] of the image selected for the overlay.
     * Also resets the corner points for perspective warping, as the new image will have different dimensions.
     *
     * @param uri The content [Uri] of the selected overlay image.
     */
    fun onOverlayImageSelected(uri: Uri) {
        _uiState.update { it.copy(overlayImageUri = uri, points = emptyList()) }
    }

    /**
     * Updates the opacity of the overlay image.
     *
     * @param opacity The new opacity value, typically between 0.0f and 1.0f.
     */
    fun onOpacityChanged(opacity: Float) {
        _uiState.update { it.copy(opacity = opacity) }
    }

    /**
     * Updates the contrast of the overlay image.
     *
     * @param contrast The new contrast value.
     */
    fun onContrastChanged(contrast: Float) {
        _uiState.update { it.copy(contrast = contrast) }
    }

    /**
     * Updates the saturation of the overlay image.
     *
     * @param saturation The new saturation value.
     */
    fun onSaturationChanged(saturation: Float) {
        _uiState.update { it.copy(saturation = saturation) }
    }

    /**
     * Updates the scale of the overlay image based on a zoom gesture.
     * The new scale is multiplied by the existing scale.
     *
     * @param scale The scale factor from the gesture.
     */
    fun onScaleChanged(scale: Float) {
        _uiState.update { it.copy(scale = it.scale * scale) }
    }

    /**
     * Updates the rotation of the overlay image based on a rotation gesture.
     * The new rotation is added to the existing rotation.
     *
     * @param rotation The change in rotation from the gesture.
     */
    fun onRotationChanged(rotation: Float) {
        _uiState.update { it.copy(rotation = it.rotation + rotation) }
    }

    /**
     * Sets the initial four corner points for the perspective warp. This is typically called
     * once the overlay image has been laid out for the first time.
     *
     * @param points The initial list of four corner points, usually matching the image bounds.
     */
    fun onPointsInitialized(points: List<Offset>) {
        _uiState.update { it.copy(points = points) }
    }

    /**
     * Updates the position of a single corner point for the perspective warp, identified by its index.
     *
     * @param index The index of the point to update (0-3).
     * @param newPosition The new position of the point as an [Offset].
     */
    fun onPointChanged(index: Int, newPosition: Offset) {
        _uiState.update { currentState ->
            val updatedPoints = currentState.points.toMutableList()
            if (index in 0..3) {
                updatedPoints[index] = newPosition
            }
            currentState.copy(points = updatedPoints)
        }
    }

    /**
     * Changes the current editor mode.
     *
     * @param mode The [EditorMode] to switch to.
     */
    fun onEditorModeChanged(mode: EditorMode) {
        _uiState.update { it.copy(editorMode = mode) }
    }

    /**
     * Marks the onboarding flow for a specific [EditorMode] as complete and persists this
     * information to [SharedPreferences] to prevent it from showing again.
     *
     * @param mode The [EditorMode] for which the onboarding has been completed.
     */
    fun onOnboardingComplete(mode: EditorMode) {
        _uiState.update { currentState ->
            val updatedModes = currentState.completedOnboardingModes + mode
            sharedPreferences.edit()
                .putStringSet("completed_onboarding", updatedModes.map { it.name }.toSet())
                .apply()
            currentState.copy(completedOnboardingModes = updatedModes)
        }
    }
}