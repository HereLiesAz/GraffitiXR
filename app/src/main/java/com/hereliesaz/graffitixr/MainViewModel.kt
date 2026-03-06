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
    val captureStep: CaptureStep = CaptureStep.NONE
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

    fun onConfirmTargetCreation(bitmap: Bitmap? = null) {
        _uiState.update {
            it.copy(isCapturingTarget = false, captureStep = CaptureStep.NONE)
        }
        bitmap ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val currentProject = projectRepository.currentProject.value ?: return@launch

            val fp = slamManager.generateFingerprint(bitmap)

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
                captureStep = CaptureStep.NONE
            )
        }
    }
}