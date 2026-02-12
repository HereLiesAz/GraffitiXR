package com.hereliesaz.graffitixr.feature.ar

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import com.hereliesaz.graffitixr.common.model.ArUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class ArViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(ArUiState())
    val uiState: StateFlow<ArUiState> = _uiState.asStateFlow()

    private val _newTargetImage = MutableSharedFlow<Pair<Bitmap?, String>>()
    val newTargetImage: SharedFlow<Pair<Bitmap?, String>> = _newTargetImage.asSharedFlow()

    fun togglePointCloud() {
        _uiState.update { it.copy(showPointCloud = !it.showPointCloud) }
    }

    fun toggleFlashlight() {
        _uiState.update { it.copy(isFlashlightOn = !it.isFlashlightOn) }
    }

    fun setTempCapture(bitmap: Bitmap) {
        _uiState.update { it.copy(tempCaptureBitmap = bitmap) }
    }

    fun onFrameCaptured(bitmap: Bitmap, uri: Uri) {
        _uiState.update {
            it.copy(
                capturedTargetUris = it.capturedTargetUris + uri,
                capturedTargetImages = it.capturedTargetImages + bitmap
            )
        }
        // Signal AR renderer to update database if needed
        // viewModelScope.launch { _newTargetImage.emit(bitmap to "target_${System.currentTimeMillis()}") }
    }

    fun onTargetDetected(isDetected: Boolean) {
        _uiState.update { it.copy(isTargetDetected = isDetected) }
    }

    fun updateTrackingState(state: String, planes: Int, points: Int) {
        _uiState.update {
            it.copy(
                trackingState = state,
                planeCount = planes,
                pointCloudCount = points
            )
        }
    }
}