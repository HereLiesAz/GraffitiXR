package com.hereliesaz.graffitixr

import androidx.lifecycle.ViewModel
import com.hereliesaz.graffitixr.common.model.CaptureStep
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * State representing the global application status, distinct from specific features.
 *
 * @property isTouchLocked Whether the screen touch input is disabled (Trace Mode).
 * @property showUnlockInstructions Whether to show the "how to unlock" hint.
 * @property isCapturingTarget Whether the global target creation flow is active.
 * @property captureStep The current step in the target creation wizard.
 */
data class MainUiState(
    val isTouchLocked: Boolean = false,
    val showUnlockInstructions: Boolean = false,
    val isCapturingTarget: Boolean = false,
    val captureStep: CaptureStep = CaptureStep.NONE,
    val currentScreen: String = AppScreens.AR
)

/**
 * The top-level ViewModel for the application.
 * Manages cross-cutting concerns like touch locking, global navigation states (Target Creation Flow),
 * and app-wide UI overlays.
 *
 * This ViewModel is scoped to the [MainActivity] lifecycle and survives navigation changes.
 */
@HiltViewModel
class MainViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    /**
     * Enables or disables the touch lock (used in Trace Mode).
     * When locked, most UI interactions are intercepted.
     */
    fun setTouchLocked(locked: Boolean) {
        _uiState.update { it.copy(isTouchLocked = locked) }
    }

    /**
     * Shows or hides the unlock instructions dialog.
     * Triggered when the user attempts to interact with a locked screen.
     */
    fun showUnlockInstructions(show: Boolean) {
        _uiState.update { it.copy(showUnlockInstructions = show) }
    }

    /**
     * Updates the currently active screen ID.
     * Used by the Global Background to decide which content to render.
     */
    fun setCurrentScreen(screenId: String) {
        _uiState.update { it.copy(currentScreen = screenId) }
    }

    /**
     * Initiates the AR Target Creation flow.
     * Sets the state to capture mode.
     */
    fun startTargetCapture() {
        _uiState.update {
            it.copy(
                isCapturingTarget = true,
                captureStep = CaptureStep.CAPTURE
            )
        }
    }

    /**
     * Advances or regresses the target creation step manually.
     * @param step The new [CaptureStep].
     */
    fun setCaptureStep(step: CaptureStep) {
        _uiState.update { it.copy(captureStep = step) }
    }

    /**
     * Resets the capture flow to the initial capture step (e.g., from Review back to Capture).
     */
    fun onRetakeCapture() {
        _uiState.update { it.copy(captureStep = CaptureStep.CAPTURE) }
    }

    /**
     * Finalizes the target creation and exits the flow.
     */
    fun onConfirmTargetCreation() {
        _uiState.update {
            it.copy(
                isCapturingTarget = false,
                captureStep = CaptureStep.NONE
            )
        }
    }

    /**
     * Cancels the target creation and exits the flow.
     */
    fun onCancelCaptureClicked() {
        _uiState.update {
            it.copy(
                isCapturingTarget = false,
                captureStep = CaptureStep.NONE
            )
        }
    }
}
