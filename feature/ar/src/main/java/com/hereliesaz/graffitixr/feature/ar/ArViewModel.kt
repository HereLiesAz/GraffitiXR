package com.hereliesaz.graffitixr.feature.ar

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class ArViewModel @Inject constructor(
    application: Application,
    private val projectRepository: ProjectRepository
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ArUiState())
    val uiState: StateFlow<ArUiState> = _uiState.asStateFlow()

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
        // Logic to handle the captured bitmap
        // e.g., save to disk, add to list of calibration images
        viewModelScope.launch {
            // For now, we update state to indicate a capture happened
            _uiState.update {
                it.copy(
                    isArTargetCreated = true,
                    capturedTargetUris = it.capturedTargetUris + uri,
                    capturedTargetImages = it.capturedTargetImages + bitmap
                )
            }
        }
    }

    // NEW: Progress Update
    fun onProgressUpdate(percentage: Float, bitmap: Bitmap?) {
        _uiState.update { it.copy(mappingQualityScore = percentage) }
    }

    // NEW: Calibration Points
    fun onCalibrationPointCaptured(matrix: FloatArray) {
        // Store matrix data for SLAM/Gaussian Splatting
        // _uiState.update { ... }
    }
}
