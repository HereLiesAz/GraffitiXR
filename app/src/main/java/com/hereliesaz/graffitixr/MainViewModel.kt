package com.hereliesaz.graffitixr

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.geometry.Offset
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.ar.core.Anchor
import com.google.ar.core.Pose
import com.hereliesaz.graffitixr.data.ProjectData
import com.hereliesaz.graffitixr.graphics.ArFeaturePattern
import com.hereliesaz.graffitixr.graphics.Quaternion
import com.hereliesaz.graffitixr.utils.OnboardingManager
import com.hereliesaz.graffitixr.utils.removeBackground
import com.hereliesaz.graffitixr.utils.saveBitmapToGallery
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
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

    private suspend fun removeBackground(uri: Uri): Uri? {
        return withContext(Dispatchers.IO) {
            try {
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
                savedStateHandle["uiState"] = uiState.value.copy(backgroundRemovedImageUri = resultUri, isLoading = false)
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

    fun onArObjectRotated(pitch: Float, yaw: Float, roll: Float) {
        val currentOrientation = uiState.value.arObjectOrientation

        val pitchRotation = Quaternion.fromAxisAngle(floatArrayOf(1f, 0f, 0f), pitch)
        val yawRotation = Quaternion.fromAxisAngle(floatArrayOf(0f, 1f, 0f), yaw)
        val rollRotation = Quaternion.fromAxisAngle(floatArrayOf(0f, 0f, 1f), roll)

        val newOrientation = currentOrientation * yawRotation * pitchRotation * rollRotation
        savedStateHandle["uiState"] = uiState.value.copy(arObjectOrientation = newOrientation)
    }

    fun onArObjectPanned(delta: Offset) {
        val currentPose = uiState.value.arImagePose ?: return

        // A simple approximation: translate the object on the XY plane of its current pose.
        // This doesn't account for camera perspective, so it might feel unnatural.
        // A more advanced implementation would project the 2D pan onto the 3D plane.
        val panScaleFactor = 0.005f
        val newPose = currentPose.compose(Pose.makeTranslation(delta.x * panScaleFactor, -delta.y * panScaleFactor, 0f))
        savedStateHandle["uiState"] = uiState.value.copy(arImagePose = newPose)
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

    fun onArImagePlaced(anchor: Anchor) {
        val showHint = !onboardingManager.hasSeenDoubleTapHint()
        savedStateHandle["uiState"] = uiState.value.copy(
            arImagePose = anchor.pose,
            arState = ArState.PLACED,
            showDoubleTapHint = showHint
        )
    }

    fun onArLockClicked() {
        if (uiState.value.arState == ArState.PLACED) {
            savedStateHandle["uiState"] = uiState.value.copy(arState = ArState.LOCKED)
        }
    }

    fun onCancelPlacement() {
        savedStateHandle["uiState"] = uiState.value.copy(
            arState = ArState.SEARCHING,
            arImagePose = null
        )
    }

    fun onArFeaturesDetected(arFeaturePattern: ArFeaturePattern) {
        savedStateHandle["uiState"] = uiState.value.copy(arFeaturePattern = arFeaturePattern)
    }

    fun onPlanesDetected(arePlanesDetected: Boolean) {
        savedStateHandle["uiState"] = uiState.value.copy(arePlanesDetected = arePlanesDetected)
    }

    fun onCycleRotationAxis() {
        val currentAxis = uiState.value.activeRotationAxis
        val nextAxis = when (currentAxis) {
            RotationAxis.X -> RotationAxis.Y
            RotationAxis.Y -> RotationAxis.Z
            RotationAxis.Z -> RotationAxis.X
        }
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

    fun onArDrawingProgressChanged(progress: Float) {
        savedStateHandle["uiState"] = uiState.value.copy(arDrawingProgress = progress)
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
                    colorBalanceB = uiState.value.colorBalanceB,
                    arImagePose = uiState.value.arImagePose,
                    arFeaturePattern = uiState.value.arFeaturePattern
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
                        colorBalanceB = projectData.colorBalanceB,
                        arImagePose = projectData.arImagePose,
                        arFeaturePattern = projectData.arFeaturePattern
                    )
                }
            } catch (e: Exception) {
                // Handle exception
            }
        }
    }
}
