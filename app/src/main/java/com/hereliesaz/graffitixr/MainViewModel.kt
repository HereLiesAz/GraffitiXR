package com.hereliesaz.graffitixr

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.ar.core.Anchor
import com.google.ar.core.Pose
import com.hereliesaz.graffitixr.graphics.ArFeaturePattern
import com.hereliesaz.graffitixr.graphics.Quaternion
import com.hereliesaz.graffitixr.utils.removeBackground
import com.hereliesaz.graffitixr.utils.saveBitmapToGallery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

sealed class CaptureEvent {
    object RequestCapture : CaptureEvent()
}

sealed class ProjectFileEvent {
    data class Save(val jsonString: String) : ProjectFileEvent()
    object Load : ProjectFileEvent()
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

    val uiState: StateFlow<UiState> = savedStateHandle.getStateFlow("uiState", UiState())

    private val _captureEvent = MutableSharedFlow<CaptureEvent>()
    val captureEvent = _captureEvent.asSharedFlow()

    private val _projectFileEvent = MutableSharedFlow<ProjectFileEvent>()
    val projectFileEvent = _projectFileEvent.asSharedFlow()

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
        savedStateHandle["uiState"] = uiState.value.copy(overlayImageUri = uri, backgroundRemovedImageUri = null)
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

    fun onPointsInitialized(points: List<Offset>) {
        savedStateHandle["uiState"] = uiState.value.copy(points = points)
    }

    fun onArObjectScaleChanged(scaleFactor: Float) {
        val currentScale = uiState.value.arObjectScale
        val dampedScaleFactor = 1.0f + (scaleFactor - 1.0f) * 0.25f
        savedStateHandle["uiState"] = uiState.value.copy(arObjectScale = currentScale * dampedScaleFactor)
    }

    fun onArObjectRotated(pitch: Float, yaw: Float, roll: Float) {
        val damping = 0.25f
        val currentOrientation = uiState.value.arObjectOrientation

        val pitchRotation = Quaternion.fromAxisAngle(floatArrayOf(1f, 0f, 0f), pitch * damping)
        val yawRotation = Quaternion.fromAxisAngle(floatArrayOf(0f, 1f, 0f), yaw * damping)
        val rollRotation = Quaternion.fromAxisAngle(floatArrayOf(0f, 0f, 1f), roll * damping)

        val newOrientation = currentOrientation * yawRotation * pitchRotation * rollRotation
        savedStateHandle["uiState"] = uiState.value.copy(arObjectOrientation = newOrientation)
    }

    fun onArObjectPanned(delta: Offset, cameraPose: Pose?) {
        val objectPose = uiState.value.arImagePose ?: return
        val camPose = cameraPose ?: return

        val panScaleFactor = 0.001f

        val right = camPose.xAxis
        val up = camPose.yAxis

        val worldDeltaX = right[0] * delta.x * panScaleFactor + up[0] * -delta.y * panScaleFactor
        val worldDeltaY = right[1] * delta.x * panScaleFactor + up[1] * -delta.y * panScaleFactor
        val worldDeltaZ = right[2] * delta.x * panScaleFactor + up[2] * -delta.y * panScaleFactor

        val translation = floatArrayOf(worldDeltaX, worldDeltaY, worldDeltaZ)
        val newPose = objectPose.compose(Pose.makeTranslation(translation))

        savedStateHandle["uiState"] = uiState.value.copy(arImagePose = newPose)
    }

    fun onEditorModeChanged(mode: EditorMode) {
        savedStateHandle["uiState"] = uiState.value.copy(editorMode = mode)
    }

    fun onOnboardingComplete(mode: EditorMode) {
        val currentState = uiState.value
        val updatedModes = currentState.completedOnboardingModes + mode
        savedStateHandle["uiState"] = currentState.copy(completedOnboardingModes = updatedModes)
    }

    fun onArImagePlaced(anchor: Anchor) {
        savedStateHandle["uiState"] = uiState.value.copy(
            arImagePose = anchor.pose,
            arState = ArState.PLACED
        )
    }

    fun onArLockClicked() {
        if (uiState.value.arState == ArState.PLACED) {
            savedStateHandle["uiState"] = uiState.value.copy(arState = ArState.LOCKED)
        }
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

    fun onSaveProjectClicked() {
        val currentState = uiState.value
        val projectData = ProjectData(
            overlayImageUri = currentState.overlayImageUri,
            backgroundImageUri = currentState.backgroundImageUri,
            opacity = currentState.opacity,
            contrast = currentState.contrast,
            saturation = currentState.saturation,
            scale = currentState.scale,
            rotationX = currentState.rotationX,
            rotationY = currentState.rotationY,
            rotationZ = currentState.rotationZ,
            arImagePose = currentState.arImagePose,
            arObjectScale = currentState.arObjectScale,
            arObjectOrientation = currentState.arObjectOrientation
        )

        val jsonString = Json.encodeToString(projectData)
        viewModelScope.launch {
            _projectFileEvent.emit(ProjectFileEvent.Save(jsonString))
        }
    }

    fun onLoadProjectClicked() {
        viewModelScope.launch {
            _projectFileEvent.emit(ProjectFileEvent.Load)
        }
    }

    fun loadProject(jsonString: String) {
        try {
            val projectData = Json.decodeFromString<ProjectData>(jsonString)
            savedStateHandle["uiState"] = uiState.value.copy(
                overlayImageUri = projectData.overlayImageUri,
                backgroundImageUri = projectData.backgroundImageUri,
                opacity = projectData.opacity,
                contrast = projectData.contrast,
                saturation = projectData.saturation,
                scale = projectData.scale,
                rotationX = projectData.rotationX,
                rotationY = projectData.rotationY,
                rotationZ = projectData.rotationZ,
                arImagePose = projectData.arImagePose,
                arObjectScale = projectData.arObjectScale,
                arObjectOrientation = projectData.arObjectOrientation
            )
        } catch (e: Exception) {
            Log.e("LoadProject", "Failed to load project", e)
            // Optionally, show a toast to the user
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

    fun onSettingsClicked() {
        val currentShowSettings = uiState.value.showSettings
        savedStateHandle["uiState"] = uiState.value.copy(showSettings = !currentShowSettings)
    }
}