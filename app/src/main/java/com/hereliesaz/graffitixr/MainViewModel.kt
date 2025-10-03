package com.hereliesaz.graffitixr

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.autobackgroundremover.removeBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * The central ViewModel for the application, acting as the single source of truth for the UI state
 * and the handler for all user events.
 *
 * This class follows the MVVM architecture pattern. It holds the application's UI state in a
 * [StateFlow] and exposes public functions to modify that state in response to user interactions.
 * By extending [AndroidViewModel], it can safely access the application context to interact with
 * system services like [SharedPreferences].
 *
 * @param application The application instance, provided by the ViewModel factory. Used here to
 * access SharedPreferences for persisting user data (e.g., completed onboarding).
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val sharedPreferences = application.getSharedPreferences("GraffitiXR_prefs", Context.MODE_PRIVATE)

    private val _uiState: MutableStateFlow<UiState>
    val uiState: StateFlow<UiState>

    init {
        val completedModes = sharedPreferences.getStringSet("completed_onboarding", emptySet())
            ?.mapNotNull {
                try {
                    EditorMode.valueOf(it)
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
            ?.toSet() ?: emptySet()
        _uiState = MutableStateFlow(UiState(completedOnboardingModes = completedModes))
        uiState = _uiState.asStateFlow()
    }

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
                _uiState.update { it.copy(backgroundRemovedImageUri = resultUri, isLoading = false) }
            } else {
                setLoading(false)
            }
        }
    }

    private fun setLoading(isLoading: Boolean) {
        _uiState.update { it.copy(isLoading = isLoading) }
    }

    fun onBackgroundImageSelected(uri: Uri) {
        _uiState.update { it.copy(backgroundImageUri = uri) }
    }

    fun onOverlayImageSelected(uri: Uri) {
        _uiState.update { it.copy(overlayImageUri = uri, points = emptyList(), backgroundRemovedImageUri = null) }
    }

    fun onOpacityChanged(opacity: Float) {
        _uiState.update { it.copy(opacity = opacity) }
    }

    fun onContrastChanged(contrast: Float) {
        _uiState.update { it.copy(contrast = contrast) }
    }

    fun onSaturationChanged(saturation: Float) {
        _uiState.update { it.copy(saturation = saturation) }
    }

    fun onScaleChanged(scale: Float) {
        _uiState.update { it.copy(scale = it.scale * scale) }
    }

    fun onRotationChanged(rotation: Float) {
        _uiState.update { it.copy(rotation = it.rotation + rotation) }
    }

    fun onPointsInitialized(points: List<Offset>) {
        _uiState.update { it.copy(points = points) }
    }

    fun onPointChanged(index: Int, newPosition: Offset) {
        _uiState.update { currentState ->
            val updatedPoints = currentState.points.toMutableList()
            if (index in 0..3) {
                updatedPoints[index] = newPosition
            }
            currentState.copy(points = updatedPoints)
        }
    }

    fun onEditorModeChanged(mode: EditorMode) {
        _uiState.update { it.copy(editorMode = mode) }
    }

    fun onOnboardingComplete(mode: EditorMode) {
        _uiState.update { currentState ->
            val updatedModes = currentState.completedOnboardingModes + mode
            sharedPreferences.edit()
                .putStringSet("completed_onboarding", updatedModes.map { it.name }.toSet())
                .apply()
            currentState.copy(completedOnboardingModes = updatedModes)
        }
    }
}