package com.hereliesaz.graffitixr

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import com.google.ar.core.Anchor
import com.hereliesaz.graffitixr.graphics.ArFeaturePattern
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The central ViewModel for the application, responsible for managing the UI state
 * and handling all user events.
 */
class MainViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun onEditorModeChanged(mode: EditorMode) {
        _uiState.update { it.copy(editorMode = mode) }
    }

    fun onOverlayImageSelected(uri: Uri) {
        _uiState.update {
            it.copy(
                overlayImageUri = uri,
                // Reset mode-specific states when a new image is selected
                mockupPoints = emptyList(),
                // Initialize history with a base empty state for undo/redo
                mockupPointsHistory = listOf(emptyList()),
                mockupPointsHistoryIndex = 0,
                arImagePose = null,
                arFeaturePattern = null,
                isArLocked = false,
                imageTraceScale = 1f,
                imageTraceOffset = Offset.Zero
            )
        }
    }

    fun onBackgroundImageSelected(uri: Uri) {
        _uiState.update { it.copy(backgroundImageUri = uri) }
    }

    fun onOpacityChanged(opacity: Float) {
        _uiState.update { it.copy(opacity = opacity) }
    }

    fun onContrastChanged(contrast: Float) {
        _uiState.update { it.copy(contrast = contrast) }
    }

    fun onSaturationChanged(saturation: Float) {
        _uiState.update { it.copy(saturation = saturation) }
    }

    // --- Image Trace Mode ---
    fun onImageTraceScaleChanged(scaleChange: Float) {
        _uiState.update { it.copy(imageTraceScale = it.imageTraceScale * scaleChange) }
    }

    fun onImageTraceOffsetChanged(offsetChange: Offset) {
        _uiState.update { it.copy(imageTraceOffset = it.imageTraceOffset + offsetChange) }
    }


    // --- Mock-up Mode ---
    fun onMockupPointsChanged(points: List<Offset>) {
        _uiState.update {
            val newHistory = it.mockupPointsHistory.subList(0, it.mockupPointsHistoryIndex + 1).toMutableList()
            newHistory.add(points)
            it.copy(
                mockupPoints = points,
                mockupPointsHistory = newHistory,
                mockupPointsHistoryIndex = newHistory.lastIndex
            )
        }
    }

    fun onWarpToggled() {
        _uiState.update { it.copy(isWarpEnabled = !it.isWarpEnabled) }
    }

    fun onUndoMockup() {
        _uiState.update {
            if (it.mockupPointsHistoryIndex > 0) {
                val newIndex = it.mockupPointsHistoryIndex - 1
                it.copy(
                    mockupPoints = it.mockupPointsHistory[newIndex],
                    mockupPointsHistoryIndex = newIndex
                )
            } else {
                it
            }
        }
    }

    fun onRedoMockup() {
        _uiState.update {
            if (it.mockupPointsHistoryIndex < it.mockupPointsHistory.lastIndex) {
                val newIndex = it.mockupPointsHistoryIndex + 1
                it.copy(
                    mockupPoints = it.mockupPointsHistory[newIndex],
                    mockupPointsHistoryIndex = newIndex
                )
            } else {
                it
            }
        }
    }

    fun onResetMockup() {
        _uiState.update {
            it.copy(
                mockupPoints = emptyList(),
                // Re-initialize history with a base empty state
                mockupPointsHistory = listOf(emptyList()),
                mockupPointsHistoryIndex = 0
            )
        }
    }

    // --- AR Overlay Mode ---
    fun onArImagePlaced(anchor: Anchor) {
        val poseMatrix = FloatArray(16)
        anchor.pose.toMatrix(poseMatrix, 0)
        _uiState.update { it.copy(arImagePose = poseMatrix, arFeaturePattern = null) }
        anchor.detach()
    }

    fun onArFeaturesDetected(featurePattern: ArFeaturePattern) {
        _uiState.update { it.copy(arFeaturePattern = featurePattern) }
    }

    fun onArLockToggled() {
        _uiState.update {
            val pattern = if (it.isArLocked) null else it.arFeaturePattern
            it.copy(isArLocked = !it.isArLocked, arFeaturePattern = pattern)
        }
    }

    // --- Global ---
    fun setLoading(isLoading: Boolean) {
        _uiState.update { it.copy(isLoading = isLoading) }
    }
}