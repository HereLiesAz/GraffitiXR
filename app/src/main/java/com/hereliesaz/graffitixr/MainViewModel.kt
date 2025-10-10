package com.hereliesaz.graffitixr

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.ui.geometry.Offset
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.data.ProjectData
import com.hereliesaz.graffitixr.utils.OnboardingManager
import com.hereliesaz.graffitixr.utils.convertToLineDrawing
import com.hereliesaz.graffitixr.utils.saveBitmapToGallery
import com.slowmac.autobackgroundremover.removeBackground
import com.vuforia.VuforiaJNI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

sealed class CaptureEvent {
    object RequestCapture : CaptureEvent()
}

sealed class TapFeedback {
    data class Success(val position: Offset) : TapFeedback()
    data class Failure(val position: Offset) : TapFeedback()
}

/**
 * The central ViewModel for the application, acting as the single source of truth for the UI state
 * and the handler for all user events.
 *
 * This class follows the MVVM architecture pattern. It holds the application's UI state in a
 * [StateFlow] backed by [SavedStateHandle]. This ensures that the UI state survives not only
 * configuration changes but also system-initiated process death, providing a robust user experience.
 *
 * @param application The application instance, used for accessing the application context.
 * @param savedStateHandle A handle to the saved state, provided by the ViewModel factory,
 * used to store and restore the [UiState].
 */
class MainViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    private val onboardingManager = OnboardingManager(application)

    val uiState: StateFlow<UiState> = savedStateHandle.getStateFlow("uiState", UiState(
        completedOnboardingModes = onboardingManager.getCompletedModes()
    ))

    private val _captureEvent = MutableSharedFlow<CaptureEvent>()
    val captureEvent = _captureEvent.asSharedFlow()

    private val _tapFeedback = MutableStateFlow<TapFeedback?>(null)
    val tapFeedback = _tapFeedback.asStateFlow()

    fun showTapFeedback(position: Offset, isSuccess: Boolean) {
        viewModelScope.launch {
            _tapFeedback.value = if (isSuccess) TapFeedback.Success(position) else TapFeedback.Failure(position)
            delay(500)
            _tapFeedback.value = null
        }
    }

    fun onRemoveBackgroundClicked() {
        viewModelScope.launch {
            setLoading(true)
            val uri = uiState.value.overlayImageUri
            if (uri != null) {
                try {
                    val context = getApplication<Application>().applicationContext
                    val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val source = ImageDecoder.createSource(context.contentResolver, uri)
                        ImageDecoder.decodeBitmap(source)
                    } else {
                        @Suppress("DEPRECATION")
                        MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                    }

                    val resultBitmap = bitmap.removeBackground(context)

                    val cachePath = File(context.cacheDir, "images")
                    cachePath.mkdirs()
                    val file = File(cachePath, "background_removed_${System.currentTimeMillis()}.png")
                    val fOut = FileOutputStream(file)
                    resultBitmap.compress(Bitmap.CompressFormat.PNG, 100, fOut)
                    fOut.close()

                    val newUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                    savedStateHandle["uiState"] = uiState.value.copy(backgroundRemovedImageUri = newUri, isLoading = false)
                } catch (e: Exception) {
                    e.printStackTrace()
                    setLoading(false)
                }
            } else {
                setLoading(false)
            }
        }
    }

    fun onLineDrawingClicked() {
        viewModelScope.launch {
            setLoading(true)
            val uri = uiState.value.overlayImageUri
            if (uri != null) {
                val context = getApplication<Application>().applicationContext
                val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                        decoder.isMutableRequired = true
                    }
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                }.copy(Bitmap.Config.ARGB_8888, true)

                val lineDrawingBitmap = convertToLineDrawing(bitmap)

                val cachePath = File(context.cacheDir, "images")
                cachePath.mkdirs()
                val file = File(cachePath, "line_drawing_${System.currentTimeMillis()}.png")
                val fOut = FileOutputStream(file)
                lineDrawingBitmap.compress(Bitmap.CompressFormat.PNG, 100, fOut)
                fOut.close()

                val newUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                savedStateHandle["uiState"] = uiState.value.copy(overlayImageUri = newUri, isLoading = false)
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
        val showHint = !onboardingManager.hasSeenDoubleTapHint()
        savedStateHandle["uiState"] = uiState.value.copy(
            overlayImageUri = uri,
            backgroundRemovedImageUri = null,
            showDoubleTapHint = showHint
        )

        viewModelScope.launch {
            val context = getApplication<Application>().applicationContext
            val texture = com.hereliesaz.graffitixr.utils.Texture.loadTextureFromUri(context, uri)
            if (texture != null) {
                VuforiaJNI.setOverlayTexture(texture.width, texture.height, texture.data!!)
            }
        }
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

    fun onScaleChanged(scaleFactor: Float) {
        val currentScale = uiState.value.scale
        savedStateHandle["uiState"] = uiState.value.copy(scale = currentScale * scaleFactor)
    }

    fun onOffsetChanged(offset: Offset) {
        savedStateHandle["uiState"] = uiState.value.copy(offset = uiState.value.offset + offset)
    }

    fun onRotationZChanged(rotationDelta: Float) {
        val currentRotation = uiState.value.rotationZ
        savedStateHandle["uiState"] = uiState.value.copy(rotationZ = currentRotation + rotationDelta)
    }

    fun onArObjectScaleChanged(scaleFactor: Float) {
        val currentScale = uiState.value.arObjectScale
        savedStateHandle["uiState"] = uiState.value.copy(arObjectScale = currentScale * scaleFactor)
    }

    fun onEditorModeChanged(mode: EditorMode) {
        savedStateHandle["uiState"] = uiState.value.copy(editorMode = mode)
    }

    fun onOnboardingComplete(mode: EditorMode) {
        onboardingManager.completeMode(mode)
        val updatedModes = onboardingManager.getCompletedModes()
        savedStateHandle["uiState"] = uiState.value.copy(completedOnboardingModes = updatedModes)
    }

    fun onDoubleTapHintDismissed() {
        onboardingManager.setDoubleTapHintSeen()
        savedStateHandle["uiState"] = uiState.value.copy(showDoubleTapHint = false)
    }

    fun onCycleRotationAxis() {
        val currentAxis = uiState.value.activeRotationAxis
        val nextAxis = when (currentAxis) {
            RotationAxis.X -> RotationAxis.Y
            RotationAxis.Y -> RotationAxis.Z
            RotationAxis.Z -> RotationAxis.X
        }
        Toast.makeText(getApplication(), "Rotating around ${nextAxis.name} axis", Toast.LENGTH_SHORT).show()
        savedStateHandle["uiState"] = uiState.value.copy(
            activeRotationAxis = nextAxis,
            showRotationAxisFeedback = true
        )
    }

    fun onRotationXChanged(delta: Float) {
        savedStateHandle["uiState"] = uiState.value.copy(rotationX = uiState.value.rotationX + delta)
    }

    fun onRotationYChanged(delta: Float) {
        savedStateHandle["uiState"] = uiState.value.copy(rotationY = uiState.value.rotationY + delta)
    }

    fun onFeedbackShown() {
        viewModelScope.launch {
            delay(1000) // Keep feedback visible for 1 second
            savedStateHandle["uiState"] = uiState.value.copy(showRotationAxisFeedback = false)
        }
    }

    /**
     * Handles the save button click event by emitting a capture request event.
     */
    fun onSaveClicked() {
        viewModelScope.launch {
            _captureEvent.emit(CaptureEvent.RequestCapture)
        }
    }

    /**
     * Saves a captured bitmap to the gallery.
     *
     * @param bitmap The bitmap to save.
     */
    fun saveCapturedBitmap(bitmap: Bitmap) {
        viewModelScope.launch {
            setLoading(true)
            saveBitmapToGallery(getApplication(), bitmap)
            withContext(Dispatchers.Main) {
                setLoading(false)
            }
        }
    }

    fun onColorBalanceRChanged(value: Float) {
        savedStateHandle["uiState"] = uiState.value.copy(colorBalanceR = value)
    }

    fun onColorBalanceGChanged(value: Float) {
        savedStateHandle["uiState"] = uiState.value.copy(colorBalanceG = value)
    }

    fun onColorBalanceBChanged(value: Float) {
        savedStateHandle["uiState"] = uiState.value.copy(colorBalanceB = value)
    }

    fun saveProject(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val projectData = ProjectData(
                    backgroundImageUri = uiState.value.backgroundImageUri,
                    overlayImageUri = uiState.value.overlayImageUri,
                    opacity = uiState.value.opacity,
                    contrast = uiState.value.contrast,
                    saturation = uiState.value.saturation,
                    colorBalanceR = uiState.value.colorBalanceR,
                    colorBalanceG = uiState.value.colorBalanceG,
                    colorBalanceB = uiState.value.colorBalanceB
                )
                val jsonString = Json.encodeToString(projectData)
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                    it.write(jsonString.toByteArray())
                }
            } catch (e: Exception) {
                // Handle exception
            }
        }
    }

    fun loadProject(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val jsonString = getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader().use { it?.readText() }
                if (jsonString != null) {
                    val projectData = Json.decodeFromString<ProjectData>(jsonString)
                    savedStateHandle["uiState"] = uiState.value.copy(
                        backgroundImageUri = projectData.backgroundImageUri,
                        overlayImageUri = projectData.overlayImageUri,
                        opacity = projectData.opacity,
                        contrast = projectData.contrast,
                        saturation = projectData.saturation,
                        colorBalanceR = projectData.colorBalanceR,
                        colorBalanceG = projectData.colorBalanceG,
                        colorBalanceB = projectData.colorBalanceB
                    )
                }
            } catch (e: Exception) {
                // Handle exception
            }
        }
    }

    fun onArStateChanged(newState: ArState) {
        savedStateHandle["uiState"] = uiState.value.copy(arState = newState)
    }

    fun onTargetCreationStateChanged(newState: TargetCreationState) {
        savedStateHandle["uiState"] = uiState.value.copy(targetCreationState = newState)
    }

    fun onCreateTargetClicked() {
        createImageTarget()
    }

    fun createImageTarget() {
        viewModelScope.launch {
            onTargetCreationStateChanged(TargetCreationState.CREATING)
            val success = withContext(Dispatchers.IO) {
                VuforiaJNI.createImageTarget()
            }
            if (success) {
                onTargetCreationStateChanged(TargetCreationState.SUCCESS)
                onArStateChanged(ArState.PLACED)
            } else {
                onTargetCreationStateChanged(TargetCreationState.ERROR)
            }
        }
    }
}
