// FILE: app/src/main/java/com/hereliesaz/graffitixr/MainViewModel.kt
package com.hereliesaz.graffitixr

import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.common.model.CaptureStep
import com.hereliesaz.graffitixr.data.ProjectManager
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    // True after target creation until user confirms the overlay is on the right wall.
    val planeConfirmationPending: Boolean = false,
    // True when the user tapped "Re-detect" and is being walked through realignment.
    val isInPlaneRealignment: Boolean = false,
    // True when the current capture was initiated via the tap-to-target path (Phase 4).
    val captureOriginatedFromTap: Boolean = false
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val slamManager: SlamManager,
    private val projectManager: ProjectManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

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

    fun confirmPlane() {
        _uiState.update { it.copy(planeConfirmationPending = false, isInPlaneRealignment = false) }
    }

    fun beginPlaneRealignment() {
        _uiState.update { it.copy(isInPlaneRealignment = true) }
    }

    fun endPlaneRealignment() {
        _uiState.update { it.copy(isInPlaneRealignment = false) }
    }

    fun onConfirmTargetCreation(bitmap: Bitmap? = null, selectionMask: Bitmap? = null) {
        _uiState.update {
            it.copy(isCapturingTarget = false, captureStep = CaptureStep.NONE, planeConfirmationPending = true)
        }
        bitmap ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val currentProject = projectRepository.currentProject.value ?: return@launch

            // CRITICAL FIX: The ARCore camera sensor is natively landscape.
            // The UI passed us a portrait bitmap (rotated 90 degrees for display).
            // If we extract features on the portrait image, the SLAM map will be rotated 90 degrees
            // out of phase with the live camera feed, rendering all splats sideways.
            // We MUST un-rotate the bitmap back to the sensor's native orientation.
            val isRotatedForUi = bitmap.height > bitmap.width
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

            // Use masked detection if the user refined the selection, otherwise detect everywhere.
            val fp = slamManager.generateFingerprintMasked(sensorBmp, sensorMask)

            // If the user pointed at a blank wall with no texture, ORB will fail to find points.
            if (fp == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Target lacks visual detail. Please use a surface with more contrast.", Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            slamManager.setTargetFingerprint(
                fp.descriptorsData,
                fp.descriptorsRows,
                fp.descriptorsCols,
                fp.descriptorsType,
                fp.points3d.toFloatArray()
            )

            projectManager.saveProject(
                context = context,
                projectData = currentProject.copy(fingerprint = fp),
                // Keep the portrait version for the UI library thumbnail
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
                captureOriginatedFromTap = false
            )
        }
    }
}