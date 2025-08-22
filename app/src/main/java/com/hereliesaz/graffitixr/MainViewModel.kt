package com.hereliesaz.graffitixr

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ar.core.Pose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val imageUri: Uri? = null,
    val opacity: Float = 1f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val brightness: Float = 0f,
    val activeSlider: SliderType? = null,
    val placementMode: Boolean = true,
    val lockedPose: Pose? = null,
    val cameraPose: Pose? = null,
    val isProcessing: Boolean = false,
    val snackbarMessage: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    fun onSelectImage(uri: Uri?) {
        _uiState.update { it.copy(imageUri = uri) }
    }

    fun onRemoveBgClicked() {
        uiState.value.imageUri?.let { uri ->
            viewModelScope.launch {
                _uiState.update { it.copy(isProcessing = true) }
                val result = withContext(Dispatchers.IO) {
                    removeBackground(getApplication(), uri)
                }
                result.onSuccess { newUri ->
                    _uiState.update { it.copy(imageUri = newUri) }
                }.onFailure {
                    _uiState.update { it.copy(snackbarMessage = "Background removal failed.") }
                }
                _uiState.update { it.copy(isProcessing = false) }
            }
        }
    }

    fun onLockMural() {
        uiState.value.cameraPose?.let {
            val translation = floatArrayOf(0f, 0f, -2f)
            val rotation = floatArrayOf(0f, 0f, 0f, 1f)
            val lockedPose = it.compose(Pose(translation, rotation))
            _uiState.update { state ->
                state.copy(
                    lockedPose = lockedPose,
                    placementMode = false
                )
            }
        }
    }

    fun onResetMural() {
        _uiState.update {
            it.copy(
                placementMode = true,
                lockedPose = null
            )
        }
    }

    fun onSliderSelected(sliderType: SliderType?) {
        _uiState.update { it.copy(activeSlider = sliderType) }
    }

    fun onOpacityChange(value: Float) {
        _uiState.update { it.copy(opacity = value) }
    }

    fun onContrastChange(value: Float) {
        _uiState.update { it.copy(contrast = value) }
    }

    fun onSaturationChange(value: Float) {
        _uiState.update { it.copy(saturation = value) }
    }

    fun onBrightnessChange(value: Float) {
        _uiState.update { it.copy(brightness = value) }
    }

    fun onCameraPoseChange(pose: Pose?) {
        _uiState.update { it.copy(cameraPose = pose) }
    }

    fun onSnackbarMessageShown() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }
}
