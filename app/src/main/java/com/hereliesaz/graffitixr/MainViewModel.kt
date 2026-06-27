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
    // Whether the current project already has a saved target fingerprint. null = not yet resolved
    // (project still loading) so AR entry can wait instead of racing; true/false drives whether AR
    // entry auto-selects the Target button (no target yet) or drops straight into layer editing.
    val hasExistingTarget: Boolean? = null,
    // True once the user has successfully created a target in this app session.
    // Prevents auto-starting target capture on AR re-entry within the same process.
    val targetCapturedThisSession: Boolean = false,
    val captureStep: CaptureStep = CaptureStep.NONE,
    // Phase 4: True while the user is in "tap your painted marks" mode.
    val isWaitingForTap: Boolean = false,
    // True when the user tapped "Re-detect" and is being walked through realignment.
    val isInPlaneRealignment: Boolean = false,
    // True when the current capture was initiated via the tap-to-target path (Phase 4).
    val captureOriginatedFromTap: Boolean = false,
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

    init {
        // Track whether the loaded project already has a saved target so AR entry knows whether to
        // pre-select the Target button. Updated on confirm via the repository's project re-load.
        viewModelScope.launch {
            projectRepository.currentProject.collect { project ->
                // Only resolve once a real project arrives; leave it null while loading so the UI
                // doesn't briefly see "no target" and auto-start target capture by mistake.
                if (project != null) {
                    _uiState.update { it.copy(hasExistingTarget = project.fingerprint != null) }
                }
            }
        }
    }

    val completedTutorials: StateFlow<Set<String>> = settingsRepository.completedTutorials
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun markTutorialCompletePersistent(key: String) {
        viewModelScope.launch { settingsRepository.markTutorialComplete(key) }
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
        viewMatrix: FloatArray? = null,
        wallPlane: FloatArray? = null
    ) {
        if (bitmap == null || intrinsics == null || viewMatrix == null) { resetCaptureUi(); return }
        val safeIntr = intrinsics
        val safeView = viewMatrix

        if (depthBuffer == null) {
            // No depth source: build the wall fingerprint from a SINGLE capture by back-projecting
            // features onto the green ARCore wall plane (whose metric pose ARCore already solved).
            handleSingleCapture(bitmap, safeIntr, safeView, wallPlane)
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

            // (Teleological artwork base is registered from the live design composite via
            // ArViewModel.updatePaintingGuide — the design-only overlay, not blended with wall texture.)

            // Canonical patch (the captured marks) for the distortion head — fed live AND persisted on the
            // fingerprint so the head survives reload. Inert unless its model is bundled.
            val patch = grayPatchBytes(sensorBmp)
            slamManager.setWallPatchBytes(patch, PATCH_SIZE)

            projectManager.saveProject(
                context = context,
                projectData = currentProject.copy(fingerprint = fp.copy(patchData = patch)),
                targetImages = listOf(bitmap)
            )

            projectRepository.loadProject(currentProject.id)
            _uiState.update { it.copy(targetCapturedThisSession = true) }

            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Target Saved & Locked", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * The canonical distortion-head patch as a raw [PATCH_SIZE]x[PATCH_SIZE] gray byte buffer (row-major
     * luma). Computed in Kotlin so the exact bytes are both fed to native and persisted on the
     * Fingerprint — keeping the capture-time and reload-time patch identical.
     */
    private fun grayPatchBytes(bitmap: Bitmap, size: Int = PATCH_SIZE): ByteArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, size, size, true)
        val px = IntArray(size * size)
        scaled.getPixels(px, 0, size, 0, 0, size, size)
        if (scaled != bitmap) scaled.recycle()
        val out = ByteArray(size * size)
        for (i in px.indices) {
            val p = px[i]
            val r = (p ushr 16) and 0xFF; val g = (p ushr 8) and 0xFF; val b = p and 0xFF
            out[i] = ((r * 299 + g * 587 + b * 114) / 1000).toByte() // BT.601 luma
        }
        return out
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

    /**
     * Depth-off, SINGLE-capture target creation. The tap must land on a green (parallel, in-range)
     * ARCore wall plane — ARCore has already solved that plane's metric pose, so we back-project the
     * captured features onto it ([MetricFingerprintBuilder.buildSingle]) instead of triangulating a
     * second view. No green plane → refuse and guide the artist to face a wall.
     */
    private fun handleSingleCapture(bitmap: Bitmap, intr: FloatArray, view: FloatArray, wallPlane: FloatArray?) {
        if (wallPlane == null || wallPlane.size < 6) {
            resetCaptureUi()
            Toast.makeText(context, notOnGreenWallMessage, Toast.LENGTH_LONG).show()
            return
        }
        resetCaptureUi()
        val planePoint = floatArrayOf(wallPlane[0], wallPlane[1], wallPlane[2])
        val planeNormal = floatArrayOf(wallPlane[3], wallPlane[4], wallPlane[5])
        val anchor = slamManager.getAnchorTransform()
        // Clear any prior marks-centering override; the builder re-publishes it on a successful build.
        slamManager.overlayMarkCenterLocal = null
        viewModelScope.launch(Dispatchers.IO) {
            val currentProject = projectRepository.currentProject.value ?: return@launch
            val fp = MetricFingerprintBuilder.buildSingle(
                slamManager, bitmap, view, intr, planePoint, planeNormal, anchor
            )
            if (fp == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context,
                        "Not enough texture on the wall to lock a target. Try a more detailed area.",
                        Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            // Canonical patch (keyframe of the marks) for the distortion head — fed live AND persisted.
            val patch = grayPatchBytes(bitmap)
            slamManager.setWallPatchBytes(patch, PATCH_SIZE)

            projectManager.saveProject(
                context = context,
                projectData = currentProject.copy(
                    fingerprint = fp.copy(patchData = patch),
                    // Persist the capture's intrinsics + anchor — the exact values just fed to
                    // restoreWallFingerprintMetric — so reload relocalizes with the true intrinsics.
                    fingerprintIntrinsics = intr.toList(),
                    fingerprintAnchor = anchor.toList(),
                ),
                targetImages = listOf(bitmap)
            )
            projectRepository.loadProject(currentProject.id)
            _uiState.update { it.copy(targetCapturedThisSession = true) }
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Target Saved & Locked", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Shared guidance shown when single-capture target creation isn't aimed at a green wall plane
    // (used by both the pre-review gate and the handleSingleCapture backstop). Toast-in-VM matches the
    // existing capture toasts here; a full AppStrings/event-channel i18n pass is a separate cleanup.
    private val notOnGreenWallMessage =
        "Aim at a wall shown in green (face it straight-on, within ~3 m), then tap your marks."

    /**
     * The single-capture tap wasn't on a green (parallel, in-range) wall plane. Guide the artist;
     * the capture frame is discarded separately (ArViewModel.clearCaptureForRetry) so they stay in
     * tap mode and can simply re-aim and tap again.
     */
    fun notifyTargetNotOnWall() {
        Toast.makeText(context, notOnGreenWallMessage, Toast.LENGTH_LONG).show()
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

    private companion object {
        const val PATCH_SIZE = 256 // distortion-head canonical patch is 256x256 gray
    }
}