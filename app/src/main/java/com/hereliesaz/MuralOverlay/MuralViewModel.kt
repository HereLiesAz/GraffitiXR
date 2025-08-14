package com.hereliesaz.MuralOverlay

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MuralState(
    val imageUri: Uri? = null,
    val opacity: Float = 1.0f,
    val contrast: Float = 1.0f,
    val saturation: Float = 1.0f
)

class MuralViewModel : ViewModel() {

    private val _state = MutableStateFlow(MuralState())
    val state: StateFlow<MuralState> = _state.asStateFlow()

    fun onImageSelected(uri: Uri?) {
        viewModelScope.launch {
            _state.update { it.copy(imageUri = uri) }
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
