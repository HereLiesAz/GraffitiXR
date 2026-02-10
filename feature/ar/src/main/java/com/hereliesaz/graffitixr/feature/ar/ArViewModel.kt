package com.hereliesaz.graffitixr.feature.ar

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class ArViewModel @Inject constructor(
    private val projectRepository: ProjectRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ArUiState())
    val uiState: StateFlow<ArUiState> = _uiState.asStateFlow()

    private val _newTargetImage = MutableSharedFlow<Pair<Bitmap, String>>()
    val newTargetImage: SharedFlow<Pair<Bitmap, String>> = _newTargetImage.asSharedFlow()

    fun togglePointCloud() {
        val newState = !_uiState.value.showPointCloud
        _uiState.update { it.copy(showPointCloud = newState) }
    }

    fun toggleFlashlight() {
        val newState = !_uiState.value.isFlashlightOn
        _uiState.update { it.copy(isFlashlightOn = newState) }
    }

    // NEW: Frame Capture
    fun onFrameCaptured(bitmap: Bitmap, uri: Uri) {
        viewModelScope.launch {
            // Update local state
            _uiState.update {
                it.copy(
                    isArTargetCreated = true,
                    capturedTargetUris = it.capturedTargetUris + uri,
                    capturedTargetImages = it.capturedTargetImages + bitmap
                )
            }

            // Signal AR View to update database
            _newTargetImage.emit(bitmap to uri.toString())

            // Sync to Project Repository
            projectRepository.updateProject { project ->
                project.copy(
                    targetImageUris = project.targetImageUris + uri
                )
            }
        }
    }

    // NEW: Progress Update
    fun onProgressUpdate(percentage: Float, bitmap: Bitmap?) {
        _uiState.update { it.copy(mappingQualityScore = percentage) }
        viewModelScope.launch {
            projectRepository.updateProject { project ->
                project.copy(progressPercentage = percentage)
            }
        }
    }

    // NEW: Calibration Points
    fun onCalibrationPointCaptured(matrix: FloatArray) {
        // Assuming we store calibration in GraffitiProject somehow
        // For now, no-op or specific logic needed
    }
}
