// FILE: app/src/main/java/com/hereliesaz/graffitixr/MainViewModel.kt
package com.hereliesaz.graffitixr

import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import java.nio.ByteBuffer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.common.model.CaptureStep
import com.hereliesaz.graffitixr.data.ProjectManager
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class MainUiState(
    val isTouchLocked: Boolean = false,
    val showUnlockInstructions: Boolean = false,
    val isCapturingTarget: Boolean = false,
    val captureStep: CaptureStep = CaptureStep.NONE,
    // Phase 4: True while the user is in "tap your painted marks" mode.
    val isWaitingForTap: Boolean = false,
    // True when the user tapped "Re-detect" and is being walked through realignment.
    val isInPlaneRealignment: Boolean = false,
    // True when the current capture was initiated via the tap-to-target path (Phase 4).
    val captureOriginatedFromTap: Boolean = false,
    val tutorialCompleted: Map<String, Boolean> = emptyMap(),
    // When true, interacting with a rail item surfaces that item's existing tutorial text.
    val tutorialModeActive: Boolean = false,
    // The rail-item id whose tutorial text is currently being shown (null = none).
    val currentTutorialId: String? = null
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val slamManager: SlamManager,
    private val projectManager: ProjectManager,
    private val settingsRepository: SettingsRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    val completedTutorials: StateFlow<Set<String>> = settingsRepository.completedTutorials
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun markTutorialCompletePersistent(key: String) {
        viewModelScope.launch { settingsRepository.markTutorialComplete(key) }
    }

    /** Toggle the tutorial walkthrough mode. Tapping rail items only shows text while this is on. */
    fun toggleTutorialMode() {
        _uiState.update { it.copy(tutorialModeActive = !it.tutorialModeActive, currentTutorialId = null) }
    }

    /**
     * Called on every rail interaction. Surfaces that item's existing tutorial text, gated by
     * [shouldShowTutorial]. We don't author new text here — [id] is looked up against the existing
     * onboarding/help strings at the call site.
     */
    fun onRailTap(id: String) {
        if (shouldShowTutorial(id)) {
            _uiState.update { it.copy(currentTutorialId = id) }
        }
    }

    /** Dismiss the currently shown tutorial text and remember it as seen so it won't nag again. */
    fun dismissCurrentTutorial() {
        _uiState.value.currentTutorialId?.let { markTutorialCompletePersistent(it) }
        _uiState.update { it.copy(currentTutorialId = null) }
    }

    /**
     * The single policy gate for the whole tap→tutorial flow. Currently: only while tutorial mode
     * is on, and only the first time each item is tapped (until-seen). To show on *every* tap
     * instead, change the body to `return true`.
     */
    private fun shouldShowTutorial(id: String): Boolean {
        val st = _uiState.value
        return st.tutorialModeActive && id !in completedTutorials.value
    }

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
                // Phase 4: Skip the auto-capture step — user taps their painted marks instead.
                captureStep = CaptureStep.NONE,
                isWaitingForTap = true,
                captureOriginatedFromTap = true
            )
        }
    }

    fun confirmTapCapture() {
        _uiState.update {
            it.copy(
                isWaitingForTap = false,
                captureStep = CaptureStep.REVIEW
            )
        }
    }

    fun cancelTapMode() {
        _uiState.update {
            it.copy(
                isCapturingTarget = false,
                captureStep = CaptureStep.NONE,
                isWaitingForTap = false,
                captureOriginatedFromTap = false
            )
        }
    }

    fun setCaptureStep(step: CaptureStep) {
        _uiState.update { it.copy(captureStep = step) }
    }

    fun onRetakeCapture() {
        val fromTap = _uiState.value.captureOriginatedFromTap
        _uiState.update {
            it.copy(
                captureStep = if (fromTap) CaptureStep.NONE else CaptureStep.CAPTURE,
                isWaitingForTap = fromTap
            )
        }
    }

    fun beginPlaneRealignment() {
        _uiState.update { it.copy(isInPlaneRealignment = true) }
    }

    fun endPlaneRealignment() {
        _uiState.update { it.copy(isInPlaneRealignment = false) }
    }

    fun onConfirmTargetCreation(
        bitmap: Bitmap? = null,
        selectionMask: Bitmap? = null,
        depthBuffer: ByteBuffer? = null,
        depthW: Int = 0,
        depthH: Int = 0,
        depthStride: Int = 0,
        intrinsics: FloatArray? = null,
        viewMatrix: FloatArray? = null
    ) {
        _uiState.update {
            it.copy(
                isCapturingTarget = false,
                captureStep = CaptureStep.NONE,
                isWaitingForTap = false,
                captureOriginatedFromTap = false
            )
        }
        bitmap ?: return
        val safeDepth = depthBuffer ?: return
        val safeIntr = intrinsics ?: return
        val safeView = viewMatrix ?: return

        viewModelScope.launch(Dispatchers.IO) {
            val currentProject = projectRepository.currentProject.value ?: return@launch

            val display = (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay
            val isPortrait = display.rotation == android.view.Surface.ROTATION_0 || display.rotation == android.view.Surface.ROTATION_180
            val isRotatedForUi = isPortrait && bitmap.height > bitmap.width

            val sensorBmp = if (isRotatedForUi) {
                val matrix = android.graphics.Matrix().apply { postRotate(-90f) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                bitmap
            }

            val sensorMask = if (isRotatedForUi && selectionMask != null) {
                val matrix = android.graphics.Matrix().apply { postRotate(-90f) }
                Bitmap.createBitmap(selectionMask, 0, 0, selectionMask.width, selectionMask.height, matrix, true)
            } else {
                selectionMask
            }

            val fp = slamManager.setWallFingerprint(
                sensorBmp, sensorMask, safeDepth,
                depthW, depthH, depthStride,
                safeIntr, safeView
            )

            if (fp == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Target lacks visual detail. Please use a surface with more contrast.", Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            projectManager.saveProject(
                context = context,
                projectData = currentProject.copy(fingerprint = fp),
                targetImages = listOf(bitmap)
            )

            projectRepository.loadProject(currentProject.id)

            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Target Saved & Locked", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun onCancelCaptureClicked() {
        _uiState.update {
            it.copy(
                isCapturingTarget = false,
                captureStep = CaptureStep.NONE,
                isWaitingForTap = false,
                captureOriginatedFromTap = false
            )
        }
    }

    fun markTutorialCompleted(tutorialId: String) {
        _uiState.update { currentState ->
            currentState.copy(
                tutorialCompleted = currentState.tutorialCompleted + (tutorialId to true)
            )
        }
    }
}