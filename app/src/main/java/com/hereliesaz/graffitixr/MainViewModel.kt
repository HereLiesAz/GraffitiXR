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
import com.hereliesaz.graffitixr.graphics.ArFeaturePattern
import com.hereliesaz.graffitixr.graphics.Quaternion
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

    fun onRotationChanged(rotation: Float) {
        savedStateHandle["uiState"] = uiState.value.copy(rotation = uiState.value.rotation + rotation)
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

    fun onArObjectScaleChanged(scaleFactor: Float) {
        val currentScale = uiState.value.arObjectScale
        savedStateHandle["uiState"] = uiState.value.copy(arObjectScale = currentScale * scaleFactor)
    }

    fun onArObjectRotationChanged(rotationDelta: Float) {
        val currentRotation = uiState.value.arObjectRotation
        savedStateHandle["uiState"] = uiState.value.copy(arObjectRotation = currentRotation + rotationDelta)
    }
}