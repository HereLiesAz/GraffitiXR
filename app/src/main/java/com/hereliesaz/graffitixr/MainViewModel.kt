package com.hereliesaz.graffitixr

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.common.model.*
import com.hereliesaz.graffitixr.data.ProjectManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * REFACTORED: No longer implements EditorActions.
 * Responsibilities:
 * - Application Lifecycle (Resume/Pause)
 * - Global App State (Navigation, Permissions)
 * - Touch Locking (Global Overlay)
 * - Target Creation Orchestration (App-level flow)
 */
class MainViewModel(
    application: Application,
    private val projectManager: ProjectManager
) : AndroidViewModel(application) {

    // Simplified UI State
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun onResume() {
        // Global resume logic if needed
    }

    fun onPause() {
        // Global pause logic
    }

    // --- Global Overlays ---

    fun setTouchLocked(locked: Boolean) {
        _uiState.update { it.copy(isTouchLocked = locked) }
    }

    fun showUnlockInstructions() {
        _uiState.update { it.copy(showUnlockInstructions = true) }
        viewModelScope.launch {
            kotlinx.coroutines.delay(3000)
            _uiState.update { it.copy(showUnlockInstructions = false) }
        }
    }

    // --- Target Creation Flow (Orchestration) ---
    // This remains here as it interrupts the main screen flow

    fun onCancelCaptureClicked() {
        _uiState.update { it.copy(isCapturingTarget = false) }
    }

    fun onConfirmTargetCreation() {
        _uiState.update { it.copy(isArTargetCreated = true, isCapturingTarget = false) }
    }

    fun onRetakeCapture() {
        // Reset capture state logic here
    }

    // --- Project Management ---

    fun loadAvailableProjects(context: android.content.Context) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val projectIds = projectManager.getProjectList(context)
            _uiState.update { it.copy(isLoading = false) }
        }
    }
}