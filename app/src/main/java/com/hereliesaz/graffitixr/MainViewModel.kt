package com.hereliesaz.graffitixr

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.common.model.CaptureStep
import com.hereliesaz.graffitixr.common.util.ImageProcessingUtils
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    val captureStep: CaptureStep = CaptureStep.NONE
)

/**
 * The top-level ViewModel for the application.
 * Manages cross-cutting concerns like touch locking, global navigation states (Target Creation Flow),
 * and app-wide UI overlays.
 *
 * This ViewModel is scoped to the [MainActivity] lifecycle and survives navigation changes.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val slamManager: SlamManager
) : ViewModel() {

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
     * Finalizes the target creation flow.
     *
     * If [bitmap] is provided (the masked/warped target image), generates an ORB fingerprint,
     * persists it to the current project, and registers it with the SLAM engine so
     * relocalization works from the very first session — even before [tryUpdateFingerprint]
     * has had a chance to accumulate depth-backed keypoints.
     */
    fun onConfirmTargetCreation(bitmap: Bitmap? = null) {
        _uiState.update {
            it.copy(isCapturingTarget = false, captureStep = CaptureStep.NONE)
        }
        bitmap ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val currentProject = projectRepository.currentProject.value ?: return@launch

            // 1. Generate Fingerprint
            val fp = ImageProcessingUtils.generateFingerprint(bitmap)

            // 2. Register with SLAM engine immediately
            slamManager.setTargetFingerprint(
                fp.descriptorsData,
                fp.descriptorsRows,
                fp.descriptorsCols,
                fp.descriptorsType,
                fp.points3d.toFloatArray()
            )

            // 3. FIX: ACTUALLY SAVE THE BITMAP TO DISK
            projectManager.saveProject(
                context = context, // Need to inject ApplicationContext to MainViewModel
                projectData = currentProject.copy(fingerprint = fp),
                targetImages = listOf(bitmap) // This appends it to targetImageUris
            )

            // 4. Update the active repository state
            projectRepository.loadProject(currentProject.id)
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
