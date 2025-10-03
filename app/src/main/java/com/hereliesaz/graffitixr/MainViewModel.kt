package com.hereliesaz.graffitixr

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import com.google.ar.core.Session
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
    var arSession: Session? = null

    fun onEditorModeChanged(mode: EditorMode) {
        _uiState.update { it.copy(editorMode = mode) }
    }

    fun onOverlayImageSelected(uri: Uri) {
        _uiState.update {
            it.copy(
                overlayImageUri = uri,
                // Reset mode-specific states when a new image is selected
                mockupPoints = emptyList(),
                mockupPointsHistory = emptyList(),
                mockupPointsHistoryIndex = -1,
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
                mockupPointsHistory = emptyList(),
                mockupPointsHistoryIndex = -1
            )
        }
    }

    // --- AR Overlay Mode ---
    fun onArSessionInitialized(session: Session) {
        this.arSession = session
    }

    fun onArLockToggled() {
        _uiState.update { it.copy(isArLocked = !it.isArLocked) }
    }

    // --- Global ---
    fun setLoading(isLoading: Boolean) {
        _uiState.update { it.copy(isLoading = isLoading) }
    }
}