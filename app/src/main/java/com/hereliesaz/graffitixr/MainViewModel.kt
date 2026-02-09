package com.hereliesaz.graffitixr

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import com.google.ar.core.Session
import com.hereliesaz.graffitixr.common.dispatcher.DispatcherProvider
import com.hereliesaz.graffitixr.common.model.CaptureStep
import com.hereliesaz.graffitixr.common.model.UiState
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
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // AR Session State
    private var arSession: Session? = null

    init {
        Log.d("MainViewModel", "Initialized with Hilt")
    }

    fun onPermissionsResult(permissions: Map<String, Boolean>) {
        val allGranted = permissions.entries.all { it.value }
        _uiState.update { it.copy(isLoading = !allGranted) } // Placeholder for actual permission handling
    }

    fun onArSessionCreated(session: Session) {
        this.arSession = session
        _uiState.update { it.copy(isLoading = false) } // Placeholder
        Log.d("MainViewModel", "AR Session Captured")
    }

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
