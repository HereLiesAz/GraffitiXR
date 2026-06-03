// FILE: app/src/main/java/com/hereliesaz/graffitixr/MainViewModel.kt
package com.hereliesaz.graffitixr

import android.content.Context
import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.util.ImageUtils
import android.widget.Toast
import java.nio.ByteBuffer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.common.model.CaptureStep
import com.hereliesaz.graffitixr.data.ProjectManager
import com.hereliesaz.graffitixr.feature.ar.anchor.MetricFingerprintBuilder
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
    // When true, the do-it-to-advance walkthrough is running: the card shows the next action and
    // performing that exact rail interaction advances the tour. An explicit, user-toggled mode.
    val tutorialModeActive: Boolean = false,
    // The ordered walkthrough for the current mode/layers. Each step targets one rail item id and
    // carries its instruction lines. Fed by the composable via [setTutorialSequence]; empty when no
    // tour is active. Reaching the end clears this but leaves [tutorialModeActive] on.
    val tutorialSteps: List<TutorialStep> = emptyList(),
    // Index of the current step within [tutorialSteps] (which rail item the card points at).
    val tutorialStepIndex: Int = 0,
    // Line index within the current step (mode-onboarding steps have several lines; most have one).
    val tutorialLineIndex: Int = 0
)

/** One step of the do-it-to-advance walkthrough: perform an interaction on [targetId] to advance. */
data class TutorialStep(val targetId: String, val lines: List<String>)

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

    /**
     * Toggle the do-it-to-advance walkthrough. Turning it on resets progress (the composable feeds
     * the step sequence via [setTutorialSequence]); turning it off clears the tour entirely.
     */
    fun toggleTutorialMode() {
        _uiState.update {
            val turningOn = !it.tutorialModeActive
            it.copy(
                tutorialModeActive = turningOn,
                tutorialSteps = if (turningOn) it.tutorialSteps else emptyList(),
                tutorialStepIndex = 0,
                tutorialLineIndex = 0
            )
        }
    }

    /**
     * Install/refresh the ordered walkthrough for the current mode and layers. Called from the
     * composable whenever the derived sequence changes (mode switch, layer add/remove). If the
     * step the user is currently on still exists in the new sequence, position is preserved (line
     * clamped); otherwise the tour restarts at step 0 for predictability. No-op while the mode is
     * off or when the sequence is unchanged.
     */
    fun setTutorialSequence(steps: List<TutorialStep>) {
        _uiState.update { st ->
            if (!st.tutorialModeActive || steps == st.tutorialSteps) return@update st
            val currentTarget = st.tutorialSteps.getOrNull(st.tutorialStepIndex)?.targetId
            val keepIdx = currentTarget?.let { t -> steps.indexOfFirst { it.targetId == t } } ?: -1
            if (keepIdx >= 0) {
                val maxLine = steps[keepIdx].lines.lastIndex.coerceAtLeast(0)
                st.copy(
                    tutorialSteps = steps,
                    tutorialStepIndex = keepIdx,
                    tutorialLineIndex = st.tutorialLineIndex.coerceIn(0, maxLine)
                )
            } else {
                st.copy(tutorialSteps = steps, tutorialStepIndex = 0, tutorialLineIndex = 0)
            }
        }
    }

    /**
     * The interaction gate. AzNavRail reports *every* rail interaction here via azAdvanced's
     * onInteraction. Advancement happens only when the interacted [id] matches the step the card is
     * currently pointing at — that's what makes it a do-it-to-advance tour. Interacting with any
     * other item is ignored (its own action still runs at the rail). No-op while the mode is off.
     */
    fun onRailInteraction(id: String) {
        val st = _uiState.value
        if (!st.tutorialModeActive) return
        val current = st.tutorialSteps.getOrNull(st.tutorialStepIndex) ?: return
        if (id == current.targetId) advanceInternal()
    }

    /**
     * Idle/skip advancement — the per-step timer safety-net and the non-consuming screen tap call
     * this so a stuck user is never trapped. Same forward motion as a matching interaction, minus
     * the id check.
     */
    fun advanceTutorialIdle() {
        if (!_uiState.value.tutorialModeActive) return
        advanceInternal()
    }

    /**
     * Walk the current step's lines, then move to the next step; running past the last step ends
     * the tour (clears the sequence) while leaving the mode on so it re-derives on the next mode
     * change or re-toggle.
     */
    private fun advanceInternal() {
        _uiState.update { st ->
            val current = st.tutorialSteps.getOrNull(st.tutorialStepIndex) ?: return@update st
            if (st.tutorialLineIndex < current.lines.lastIndex) {
                st.copy(tutorialLineIndex = st.tutorialLineIndex + 1)
            } else if (st.tutorialStepIndex < st.tutorialSteps.lastIndex) {
                st.copy(tutorialStepIndex = st.tutorialStepIndex + 1, tutorialLineIndex = 0)
            } else {
                st.copy(tutorialSteps = emptyList(), tutorialStepIndex = 0, tutorialLineIndex = 0)
            }
        }
    }

    /** End the current walkthrough (e.g. the overlay reported no remaining text). Mode stays on. */
    fun dismissCurrentTutorial() {
        _uiState.update { it.copy(tutorialSteps = emptyList(), tutorialStepIndex = 0, tutorialLineIndex = 0) }
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
        if (bitmap == null || intrinsics == null || viewMatrix == null) { resetCaptureUi(); return }
        val safeIntr = intrinsics
        val safeView = viewMatrix

        if (depthBuffer == null) {
            // No depth source: build the wall fingerprint from two keyframes via triangulation.
            handleMetricCapture(bitmap, safeIntr, safeView)
            return
        }
        resetCaptureUi()
        val safeDepth = depthBuffer

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

            // Teleological base: register the design composited over the captured wall as the "base
            // understanding" the clean camera frame is validated against (drives painting-progress and
            // the staged self-grow). Built from the same capture frame/depth/intrinsics as the wall
            // fingerprint, so the artwork 3D coordinates align. No overlay yet => gatekeeper stays inert.
            currentProject.overlayImageUri?.let { overlayUri ->
                val design = ImageUtils.loadBitmapSync(context, overlayUri)
                if (design != null) {
                    val composite = sensorBmp.copy(Bitmap.Config.ARGB_8888, true)
                    android.graphics.Canvas(composite).drawBitmap(
                        design,
                        null,
                        android.graphics.Rect(0, 0, composite.width, composite.height),
                        android.graphics.Paint().apply { alpha = 180; isFilterBitmap = true }
                    )
                    slamManager.setArtworkFingerprint(
                        composite, safeDepth, depthW, depthH, depthStride, safeIntr, safeView
                    )
                }
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

    private fun resetCaptureUi() {
        _uiState.update {
            it.copy(
                isCapturingTarget = false,
                captureStep = CaptureStep.NONE,
                isWaitingForTap = false,
                captureOriginatedFromTap = false
            )
        }
    }

    private class MetricKf0(
        val bitmap: Bitmap, val view: FloatArray, val intr: FloatArray, val anchor: FloatArray
    )
    private var pendingMetricKf0: MetricKf0? = null

    override fun onCleared() {
        super.onCleared()
        pendingMetricKf0 = null
    }

    /**
     * Depth-off target creation: capture two keyframes a side-step apart and triangulate. The first
     * tap stores keyframe 0 and re-enters capture; the second tap supplies keyframe 1 and builds.
     */
    private fun handleMetricCapture(bitmap: Bitmap, intr: FloatArray, view: FloatArray) {
        val kf0 = pendingMetricKf0
        if (kf0 == null) {
            pendingMetricKf0 = MetricKf0(bitmap, view.copyOf(), intr.copyOf(), slamManager.getAnchorTransform())
            // Re-enter tap-capture so the next tap supplies the second view.
            _uiState.update {
                it.copy(isCapturingTarget = true, captureStep = CaptureStep.NONE,
                    isWaitingForTap = true, captureOriginatedFromTap = true)
            }
            Toast.makeText(context,
                "First view captured. Step ~20 cm to the side and tap your marks again.",
                Toast.LENGTH_LONG).show()
            return
        }
        pendingMetricKf0 = null
        resetCaptureUi()
        viewModelScope.launch(Dispatchers.IO) {
            val currentProject = projectRepository.currentProject.value ?: return@launch
            val fp = MetricFingerprintBuilder.build(
                slamManager, kf0.bitmap, kf0.view, kf0.intr, bitmap, view, intr, kf0.anchor
            )
            if (fp == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context,
                        "Not enough overlap between the two views. Try again with a smaller side-step.",
                        Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            // Teleological base (depth-off path): register the design composited over the captured wall.
            // No depth buffer here => descriptors-only base, which is enough to drive painting-progress.
            currentProject.overlayImageUri?.let { overlayUri ->
                val design = ImageUtils.loadBitmapSync(context, overlayUri)
                if (design != null) {
                    val composite = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                    android.graphics.Canvas(composite).drawBitmap(
                        design,
                        null,
                        android.graphics.Rect(0, 0, composite.width, composite.height),
                        android.graphics.Paint().apply { alpha = 180; isFilterBitmap = true }
                    )
                    slamManager.setArtworkFingerprint(composite, null, 0, 0, 0, intr, view)
                }
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
        pendingMetricKf0 = null
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