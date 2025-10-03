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
 * The central ViewModel for the application.
 * It is responsible for managing the UI state and handling all user events.
 *
 * @param application The application context, used for accessing SharedPreferences.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val sharedPreferences = application.getSharedPreferences("GraffitiXR_prefs", Context.MODE_PRIVATE)

    private val _uiState: MutableStateFlow<UiState>
    val uiState: StateFlow<UiState>

    init {
        val completedModes = sharedPreferences.getStringSet("completed_onboarding", emptySet())
            ?.mapNotNull { EditorMode.valueOf(it) }
            ?.toSet() ?: emptySet()
        _uiState = MutableStateFlow(UiState(completedOnboardingModes = completedModes))
        uiState = _uiState.asStateFlow()
    }

    /**
     * Updates the state with the selected background image URI.
     * @param uri The URI of the selected image.
     */
    fun onBackgroundImageSelected(uri: Uri) {
        _uiState.update { it.copy(backgroundImageUri = uri) }
    }

    /**
     * Updates the state with the selected overlay image URI.
     * @param uri The URI of the selected image.
     */
    fun onOverlayImageSelected(uri: Uri) {
        _uiState.update { it.copy(overlayImageUri = uri) }
    }

    /**
     * Updates the opacity of the overlay image.
     * @param opacity The new opacity value.
     */
    fun onOpacityChanged(opacity: Float) {
        _uiState.update { it.copy(opacity = opacity) }
    }

    /**
     * Updates the contrast of the overlay image.
     * @param contrast The new contrast value.
     */
    fun onContrastChanged(contrast: Float) {
        _uiState.update { it.copy(contrast = contrast) }
    }

    /**
     * Updates the saturation of the overlay image.
     * @param saturation The new saturation value.
     */
    fun onSaturationChanged(saturation: Float) {
        _uiState.update { it.copy(saturation = saturation) }
    }

    /**
     * Updates the scale of the overlay image.
     * @param scale The new scale value.
     */
    fun onScaleChanged(scale: Float) {
        _uiState.update { it.copy(scale = it.scale * scale) }
    }

    /**
     * Updates the rotation of the overlay image.
     * @param rotation The change in rotation value.
     */
    fun onRotationChanged(rotation: Float) {
        _uiState.update { it.copy(rotation = it.rotation + rotation) }
    }

    /**
     * Sets the initial corner points for the perspective warp.
     * This is typically called once the overlay image is laid out.
     * @param points The list of four corner points.
     */
    fun onPointsInitialized(points: List<Offset>) {
        _uiState.update { it.copy(points = points) }
    }

    /**
     * Updates the position of a specific corner point.
     * @param index The index of the point to update (0-3).
     * @param newPosition The new position of the point.
     */
    fun onPointChanged(index: Int, newPosition: Offset) {
        _uiState.update { currentState ->
            val updatedPoints = currentState.points.toMutableList()
            if (index >= 0 && index < updatedPoints.size) {
                updatedPoints[index] = newPosition
            }
            currentState.copy(points = updatedPoints)
        }
    }

    /**
     * Changes the current editor mode.
     * @param mode The new editor mode.
     */
    fun onEditorModeChanged(mode: EditorMode) {
        _uiState.update { it.copy(editorMode = mode) }
    }

    /**
     * Marks the onboarding for a specific mode as complete.
     * @param mode The editor mode for which the onboarding is complete.
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