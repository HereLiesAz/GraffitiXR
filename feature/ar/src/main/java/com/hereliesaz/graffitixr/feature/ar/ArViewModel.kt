package com.hereliesaz.graffitixr.feature.ar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.hereliesaz.graffitixr.common.model.CaptureEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
            // Initial update if project is already loaded
            value?.showPointCloud = _uiState.value.showPointCloud
            projectRepository.currentProject.value?.layers?.let { value?.updateLayers(it) }
        }

    init {
        // Observe Project Layers and update Renderer
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
        // Flashlight logic is usually in Activity via ArSession, but state is here
    }

    fun setArPlanesDetected(detected: Boolean) {
        _uiState.update { it.copy(isArPlanesDetected = detected) }
    }

    fun updateMappingScore(score: Float) {
        _uiState.update { it.copy(mappingQualityScore = score) }
    }

    fun onArImagePlaced() {
        _uiState.update { it.copy(arState = com.hereliesaz.graffitixr.common.model.ArState.PLACED) }
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

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.model.Fingerprint

class ArViewModel(
    private val projectRepository: ProjectRepository
) : ViewModel() {
// ... existing code ...
    fun onFrameCaptured(bitmap: Any) {
        // Placeholder
    }

    fun onProgressUpdate(percentage: Float, bitmap: Bitmap?) {
        // Update project progress?
        // This likely belongs in Editor or Dashboard via Repository update
        // But ArRenderer triggers it.
    }

    fun onCreateTargetClicked() {
        _uiState.update { it.copy(isArTargetCreated = false) } // logic?
    }

    fun onCalibrationPointCaptured(matrix: FloatArray) {
        // Calibration logic
    }

    fun onFingerprintGenerated(fingerprint: Fingerprint) {
        // Save fingerprint to project via Repository
    }

    fun saveMap() {
// ... existing code ...
        val pid = projectRepository.currentProject.value?.id ?: return
        viewModelScope.launch {
             val path = projectRepository.getMapPath(pid)
             _captureEvent.emit(CaptureEvent.RequestMapSave(path))
        }
    }
}
