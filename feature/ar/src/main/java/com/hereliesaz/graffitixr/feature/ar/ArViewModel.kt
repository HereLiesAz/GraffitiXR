package com.hereliesaz.graffitixr.feature.ar

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.common.model.ArUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ArViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ArUiState())
    val uiState: StateFlow<ArUiState> = _uiState.asStateFlow()

    // Held reference to renderer to trigger specific AR actions
    private var arRenderer: ArRenderer? = null

    fun setArRenderer(renderer: ArRenderer) {
        this.arRenderer = renderer
    }

    fun togglePointCloud() {
        val newState = !_uiState.value.showPointCloud
        _uiState.update { it.copy(showPointCloud = newState) }
        arRenderer?.setShowPointCloud(newState)
    }

    fun toggleFlashlight() {
        val newState = !_uiState.value.isFlashlightOn
        _uiState.update { it.copy(isFlashlightOn = newState) }
        // Note: Actual flashlight toggle usually happens in Activity/Camera Manager,
        // but we update state here for UI reflection.
    }

    // NEW: Frame Capture
    fun onFrameCaptured(bitmap: Bitmap) {
        // Logic to handle the captured bitmap
        // e.g., save to disk, add to list of calibration images
        viewModelScope.launch {
            // For now, we update state to indicate a capture happened
            _uiState.update {
                // In a real scenario, we'd append the new image URI to capturedTargetUris
                it.copy(isArTargetCreated = true)
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