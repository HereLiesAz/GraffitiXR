package com.hereliesaz.graffitixr.feature.editor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.InputStream

/**
 * ViewModel for the Editor screen.
 * Handles image loading, background removal, and edge detection.
 */
class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    // Helpers
    private val backgroundRemover = BackgroundRemover(application)
    private val slamManager = SlamManager() // Ensure Singleton in DI in real app

    fun onImageSelected(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true)
                val bitmap = loadBitmapFromUri(uri)
                _uiState.value = _uiState.value.copy(
                    currentImage = bitmap,
                    originalImage = bitmap, // Cache original for resets
                    isLoading = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Failed to load image: ${e.message}"
                )
            }
        }
    }

    fun onRemoveBackgroundClicked() {
        val current = _uiState.value.currentImage ?: return

        viewModelScope.launch(Dispatchers.Default) {
            _uiState.value = _uiState.value.copy(isLoading = true)

            val result = backgroundRemover.removeBackground(current)

            result.onSuccess { segmented ->
                _uiState.value = _uiState.value.copy(
                    currentImage = segmented,
                    isLoading = false
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Background removal failed: ${error.message}"
                )
            }
        }
    }

    fun onLineDrawingClicked() {
        val current = _uiState.value.currentImage ?: return

        viewModelScope.launch(Dispatchers.Default) {
            _uiState.value = _uiState.value.copy(isLoading = true)

            // Offload JNI call to background thread to avoid UI stutter
            val edgeBitmap = slamManager.detectEdges(current)

            if (edgeBitmap != null) {
                _uiState.value = _uiState.value.copy(
                    currentImage = edgeBitmap,
                    isLoading = false
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Edge detection failed."
                )
            }
        }
    }

    fun onResetImage() {
        _uiState.value = _uiState.value.copy(
            currentImage = _uiState.value.originalImage
        )
    }

    fun saveProject() {
        // TODO: Map EditorUiState back to GraffitiProject and save
        // This likely requires a Database/Repository which is outside the scope of "Wire the Editor"
    }

    fun onDismissError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        val context = getApplication<Application>().applicationContext
        val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
        return inputStream?.use {
            BitmapFactory.decodeStream(it)
        }
    }
}

data class EditorUiState(
    val currentImage: Bitmap? = null,
    val originalImage: Bitmap? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)