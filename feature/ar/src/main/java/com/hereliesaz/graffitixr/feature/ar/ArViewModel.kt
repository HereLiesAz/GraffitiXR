package com.hereliesaz.graffitixr.feature.ar

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.common.model.ArState
import com.hereliesaz.graffitixr.common.model.CaptureEvent
import com.hereliesaz.graffitixr.common.model.Fingerprint
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ArViewModel(
    private val projectRepository: ProjectRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ArUiState())
    val uiState: StateFlow<ArUiState> = _uiState.asStateFlow()

    private val _captureEvent = MutableSharedFlow<CaptureEvent>()
    val captureEvent: SharedFlow<CaptureEvent> = _captureEvent.asSharedFlow()

    var arRenderer: ArRenderer? = null
        set(value) {
            field = value
            value?.showPointCloud = _uiState.value.showPointCloud
            // Observe project layers if not already doing so
        }

    init {
        viewModelScope.launch {
            projectRepository.currentProject.collectLatest { project ->
                if (project != null) {
                    arRenderer?.updateLayers(project.layers)
                }
            }
        }
    }

    fun togglePointCloud() {
        _uiState.update { it.copy(showPointCloud = !it.showPointCloud) }
        arRenderer?.showPointCloud = _uiState.value.showPointCloud
    }

    fun toggleFlashlight() {
        _uiState.update { it.copy(isFlashlightOn = !it.isFlashlightOn) }
    }

    fun setArPlanesDetected(detected: Boolean) {
        _uiState.update { it.copy(isArPlanesDetected = detected) }
    }

    fun updateMappingScore(score: Float) {
        _uiState.update { it.copy(mappingQualityScore = score) }
    }

    fun onArImagePlaced() {
        _uiState.update { it.copy(arState = ArState.PLACED) }
    }

    fun toggleMappingMode() {
        _uiState.update { it.copy(isMappingMode = !it.isMappingMode) }
    }

    fun onTrackingFailure(message: String?) {
        _uiState.update { it.copy(qualityWarning = message) }
    }

    fun updateArtworkBounds(bounds: Any) {
        // Placeholder
    }

    fun onAnchorCreated(anchor: Any) {
        _uiState.update { it.copy(isArTargetCreated = true) }
    }

    fun onFrameCaptured(bitmap: Bitmap) {
        // Handle captured frame
    }

    fun onProgressUpdate(percentage: Float, bitmap: Bitmap?) {
        // Update progress
    }

    fun onCreateTargetClicked() {
        _uiState.update { it.copy(isArTargetCreated = false) }
    }

    fun onCalibrationPointCaptured(matrix: FloatArray) {
        // Calibration logic
    }

    fun onFingerprintGenerated(fingerprint: Fingerprint?) {
        // Save fingerprint
    }

    fun saveMap() {
        val pid = projectRepository.currentProject.value?.id ?: return
        viewModelScope.launch {
             val path = projectRepository.getMapPath(pid)
             _captureEvent.emit(CaptureEvent.RequestMapSave(path))
        }
    }
}