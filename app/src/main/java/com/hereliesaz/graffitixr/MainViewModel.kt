package com.hereliesaz.graffitixr

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.ar.core.Anchor
import com.hereliesaz.graffitixr.graphics.ArFeaturePattern
import com.hereliesaz.graffitixr.utils.removeBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * The central ViewModel for the application, acting as the single source of truth for the UI state
 * and the handler for all user events.
 *
 * This class follows the MVVM architecture pattern. It holds the application's UI state in a
 * [StateFlow] that is persisted via a [SavedStateHandle] to survive process death.
 *
 * @param application The application instance, provided by the ViewModel factory.
 * @param savedStateHandle A handle to the saved state of the ViewModel, used for persistence.
 */
class MainViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    val uiState: StateFlow<UiState> = savedStateHandle.getStateFlow("uiState", UiState())

    private suspend fun removeBackground(uri: Uri): Uri? {
        return withContext(Dispatchers.IO) {
            try {
                val context = getApplication<Application>().applicationContext
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                val bitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.isMutableRequired = true
                }

                val resultBitmap = bitmap.removeBackground()

                val cachePath = File(context.cacheDir, "images")
                cachePath.mkdirs()
                val file = File(cachePath, "background_removed_${System.currentTimeMillis()}.png")
                val fOut = FileOutputStream(file)
                resultBitmap.compress(Bitmap.CompressFormat.PNG, 100, fOut)
                fOut.close()

                FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    fun onRemoveBackgroundClicked() {
        viewModelScope.launch {
            setLoading(true)
            val uri = uiState.value.overlayImageUri
            if (uri != null) {
                val resultUri = removeBackground(uri)
                savedStateHandle["uiState"] = uiState.value.copy(
                    backgroundRemovedImageUri = resultUri,
                    isLoading = false
                )
            } else {
                setLoading(false)
            }
        }
    }

    private fun setLoading(isLoading: Boolean) {
        savedStateHandle["uiState"] = uiState.value.copy(isLoading = isLoading)
    }

    fun onBackgroundImageSelected(uri: Uri) {
        savedStateHandle["uiState"] = uiState.value.copy(backgroundImageUri = uri)
    }

    fun onOverlayImageSelected(uri: Uri) {
        savedStateHandle["uiState"] = uiState.value.copy(
            overlayImageUri = uri,
            points = emptyList(),
            backgroundRemovedImageUri = null
        )
    }

    fun onOpacityChanged(opacity: Float) {
        savedStateHandle["uiState"] = uiState.value.copy(opacity = opacity)
    }

    fun onContrastChanged(contrast: Float) {
        savedStateHandle["uiState"] = uiState.value.copy(contrast = contrast)
    }

    fun onSaturationChanged(saturation: Float) {
        savedStateHandle["uiState"] = uiState.value.copy(saturation = saturation)
    }

    fun onScaleChanged(scale: Float) {
        savedStateHandle["uiState"] = uiState.value.copy(scale = uiState.value.scale * scale)
    }

    fun onOffsetChanged(offset: Offset) {
        savedStateHandle["uiState"] = uiState.value.copy(offset = uiState.value.offset + offset)
    }

    fun onMockupPointsChanged(points: List<Offset>) {
        savedStateHandle["uiState"] = uiState.value.copy(points = points)
    }

    fun onPointChanged(index: Int, newPosition: Offset) {
        val updatedPoints = uiState.value.points.toMutableList()
        if (index in updatedPoints.indices) {
            updatedPoints[index] = newPosition
        }
        savedStateHandle["uiState"] = uiState.value.copy(points = updatedPoints)
    }

    fun onEditorModeChanged(mode: EditorMode) {
        savedStateHandle["uiState"] = uiState.value.copy(editorMode = mode)
    }

    fun onOnboardingComplete(mode: EditorMode) {
        val updatedModes = uiState.value.completedOnboardingModes + mode
        savedStateHandle["uiState"] = uiState.value.copy(completedOnboardingModes = updatedModes)
    }

    fun onArImagePlaced(anchor: Anchor) {
        // Transient AR state is not saved
    }

    fun onArFeaturesDetected(arFeaturePattern: ArFeaturePattern) {
        // Transient AR state is not saved
    }

    fun onArCoreCheckFailed(message: String) {
        savedStateHandle["uiState"] = uiState.value.copy(arErrorMessage = message)
    }
}