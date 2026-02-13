package com.hereliesaz.graffitixr.feature.ar

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import com.hereliesaz.graffitixr.common.model.ArUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class ArViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(ArUiState())
    val uiState: StateFlow<ArUiState> = _uiState.asStateFlow()

    fun toggleFlashlight() {
        _uiState.update { it.copy(isFlashlightOn = !it.isFlashlightOn) }
    }

    fun setTempCapture(bitmap: Bitmap) {
        _uiState.update { it.copy(tempCaptureBitmap = bitmap) }
    }

    fun onFrameCaptured(bitmap: Bitmap, uri: Uri) {
        // Logic to finalize a captured target image
        _uiState.update {
            it.copy(
                tempCaptureBitmap = null,
                isTargetDetected = true // Assuming capture implies we found/made a target
            )
        }
    }

    fun resetCapture() {
        _uiState.update { it.copy(tempCaptureBitmap = null) }
    }

    // FIXED: Removed 'trackingQuality' to match ArUiState definition.
    // If you need quality, add 'val mappingQuality: Float = 0f' to ArUiState.kt first.
    fun updateTrackingState(isTracking: Boolean, quality: Float) {
        _uiState.update {
            it.copy(
                isTargetDetected = isTracking,
                trackingState = if (isTracking) "Tracking" else "Searching"
            )
        }
    }
}