package com.hereliesaz.graffitixr

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import com.google.ar.core.Session
import com.hereliesaz.graffitixr.common.dispatcher.DispatcherProvider
import com.hereliesaz.graffitixr.common.model.CaptureStep
import com.hereliesaz.graffitixr.data.ProjectManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val application: Application,
    private val projectManager: ProjectManager,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    // UI State: Controls top-level navigation and system status only.
    // Content state (Layers, Tools) is now owned by EditorViewModel.
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    // AR Session State
    private var arSession: Session? = null

    init {
        Log.d("MainViewModel", "Initialized with Hilt")
        checkPermissions()
    }

    private fun checkPermissions() {
        // Handled by ActivityResultLauncher in UI
    }

    fun onPermissionsResult(permissions: Map<String, Boolean>) {
        val allGranted = permissions.entries.all { it.value }
        _uiState.update { it.copy(permissionsGranted = allGranted) }
    }

    fun onArSessionCreated(session: Session) {
        this.arSession = session
        _uiState.update { it.copy(isArSessionReady = true) }
        Log.d("MainViewModel", "AR Session Captured")
    }

    // REMOVED: onOverlayImageSelected()
    // REMOVED: layerList management
    // REASON: Moved to feature:editor / EditorViewModel to fix "Split Brain" architecture.

    fun startTargetCapture() {
        _uiState.update {
            it.copy(
                isCapturingTarget = true,
                captureStep = CaptureStep.CAPTURE
            )
        }
    }

    fun setCaptureStep(step: CaptureStep) {
        _uiState.update { it.copy(captureStep = step) }
    }

    fun onCancelCaptureClicked() {
        _uiState.update { it.copy(isCapturingTarget = false) }
    }

    fun onConfirmTargetCreation() {
        _uiState.update { it.copy(isArTargetCreated = true, isCapturingTarget = false) }
    }

    fun onRetakeCapture() {
        _uiState.update { it.copy(captureStep = CaptureStep.CAPTURE) }
    }
}
