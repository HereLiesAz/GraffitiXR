package com.hereliesaz.graffitixr

import androidx.lifecycle.ViewModel
import com.hereliesaz.graffitixr.common.model.CaptureStep
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class MainUiState(
    val isTouchLocked: Boolean = false,
    val showUnlockInstructions: Boolean = false,
    val isCapturingTarget: Boolean = false,
    val captureStep: CaptureStep = CaptureStep.NONE
)

@HiltViewModel
class MainViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    fun setTouchLocked(locked: Boolean) {
        _uiState.update { it.copy(isTouchLocked = locked) }
    }

    fun showUnlockInstructions(show: Boolean) {
        _uiState.update { it.copy(showUnlockInstructions = show) }
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

    fun onRetakeCapture() {
        _uiState.update { it.copy(captureStep = CaptureStep.CAPTURE) }
    }

    fun onConfirmTargetCreation() {
        _uiState.update {
            it.copy(
                isCapturingTarget = false,
                captureStep = CaptureStep.NONE
            )
        }
    }

    fun onCancelCaptureClicked() {
        _uiState.update {
            it.copy(
                isCapturingTarget = false,
                captureStep = CaptureStep.NONE
            )
        }
    }
}