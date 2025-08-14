package com.hereliesaz.MuralOverlay

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

import android.graphics.Bitmap

sealed class AppState {
    object Initial : AppState()
    data class MarkerCapture(val detectedMarkersCount: Int) : AppState()
    object MuralPlacement : AppState()
}

data class MuralState(
    val imageUri: Uri? = null,
    val opacity: Float = 1.0f,
    val contrast: Float = 1.0f,
    val saturation: Float = 1.0f,
    val markers: List<Bitmap> = emptyList(),
    val appState: AppState = AppState.Initial,
    val coveredMarkers: Set<Int> = emptySet()
)

class MuralViewModel : ViewModel() {

    private val _state = MutableStateFlow(MuralState())
    val state: StateFlow<MuralState> = _state.asStateFlow()

    fun onImageSelected(uri: Uri?) {
        viewModelScope.launch {
            _state.update { it.copy(imageUri = uri, appState = AppState.MuralPlacement) }
        }
    }

    fun onMarkerAdded(marker: Bitmap) {
        viewModelScope.launch {
            _state.update {
                val newMarkers = it.markers + marker
                it.copy(markers = newMarkers, appState = AppState.MarkerCapture(newMarkers.size))
            }
        }
    }

    fun onMarkerCovered(index: Int) {
        viewModelScope.launch {
            _state.update { it.copy(coveredMarkers = it.coveredMarkers + index) }
        }
    }

    fun updateDetectedMarkersCount(count: Int) {
        viewModelScope.launch {
            if (_state.value.appState is AppState.MarkerCapture) {
                _state.update { it.copy(appState = AppState.MarkerCapture(count)) }
            }
        }
    }

    fun onOpacityChanged(opacity: Float) {
        viewModelScope.launch {
            _state.update { it.copy(opacity = opacity) }
        }
    }

    fun onContrastChanged(contrast: Float) {
        viewModelScope.launch {
            _state.update { it.copy(contrast = contrast) }
        }
    }

    fun onSaturationChanged(saturation: Float) {
        viewModelScope.launch {
            _state.update { it.copy(saturation = saturation) }
        }
    }
}
