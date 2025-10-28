package com.hereliesaz.graffitixr

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.compose.ui.geometry.Offset
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.data.ProjectData
import com.hereliesaz.graffitixr.utils.OnboardingManager
import com.hereliesaz.graffitixr.utils.ProjectManager
import com.hereliesaz.graffitixr.utils.convertToLineDrawing
import com.hereliesaz.graffitixr.utils.saveBitmapToGallery
import com.slowmac.autobackgroundremover.removeBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Config
import androidx.compose.ui.graphics.BlendMode
import com.google.ar.core.Session
import com.hereliesaz.graffitixr.data.Fingerprint
import kotlinx.serialization.json.Json
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfKeyPoint
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
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
    private val savedStateHandle: SavedStateHandle,
    private val arCoreManager: ARCoreManager
) : AndroidViewModel(application) {

    private val onboardingManager = OnboardingManager(application)
    private val projectManager = ProjectManager(application)

    private var coloredPixelsBitmap: Bitmap? = null
    private var totalColoredPixels = 0

    private val undoStack = mutableListOf<UiState>()
    private val redoStack = mutableListOf<UiState>()

    val uiState: StateFlow<UiState> = savedStateHandle.getStateFlow("uiState", UiState())

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
                        val source = ImageDecoder.createSource(context.contentResolver, uri)
                        ImageDecoder.decodeBitmap(source)
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
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(source)
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
            val (width, height) = getBitmapDimensions(uri)
            coloredPixelsBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            totalColoredPixels = 0
        }
    }

    fun onOpacityChanged(opacity: Float) {
        updateState(uiState.value.copy(opacity = opacity))
    }

    fun onContrastChanged(contrast: Float) {
        updateState(uiState.value.copy(contrast = contrast))
    }

    fun onSaturationChanged(saturation: Float) {
        updateState(uiState.value.copy(saturation = saturation))
    }

    fun onScaleChanged(scaleFactor: Float) {
        val currentScale = uiState.value.scale
        updateState(uiState.value.copy(scale = currentScale * scaleFactor), isUndoable = false)
    }

    fun onOffsetChanged(offset: Offset) {
        updateState(uiState.value.copy(offset = uiState.value.offset + offset), isUndoable = false)
    }

    fun onRotationZChanged(rotationDelta: Float) {
        val currentRotation = uiState.value.rotationZ
        updateState(uiState.value.copy(rotationZ = currentRotation + rotationDelta), isUndoable = false)
    }

    fun onArObjectScaleChanged(scaleFactor: Float) {
        val currentScale = uiState.value.arObjectScale
        updateState(uiState.value.copy(arObjectScale = currentScale * scaleFactor), isUndoable = false)
    }

    fun onEditorModeChanged(mode: EditorMode) {
        updateState(uiState.value.copy(editorMode = mode))
    }

    init {
        val completedModes = onboardingManager.getCompletedModes()
        savedStateHandle["uiState"] = uiState.value.copy(completedOnboardingModes = completedModes)
    }

    fun onOnboardingComplete(mode: EditorMode, dontShowAgain: Boolean) {
        if (dontShowAgain) {
            onboardingManager.completeMode(mode)
            val updatedModes = onboardingManager.getCompletedModes()
            savedStateHandle["uiState"] = uiState.value.copy(completedOnboardingModes = updatedModes)
        }
    }

    fun onCurvesPointsChangeFinished() {
        viewModelScope.launch {
            val uri = uiState.value.overlayImageUri
            if (uri != null) {
                applyCurvesToOverlay(uri, uiState.value.curvesPoints)
            }
        }
    }

    private fun applyCurvesToOverlay(uri: Uri, points: List<Offset>) {
        viewModelScope.launch {
            setLoading(true)
            try {
                val context = getApplication<Application>().applicationContext
                val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(source)
                } else {
                    @Suppress("DEPRECATION")
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(source)
                }

                val resultBitmap = com.hereliesaz.graffitixr.utils.applyCurves(bitmap, points)

                val cachePath = File(context.cacheDir, "images")
                cachePath.mkdirs()
                val file = File(cachePath, "curves_processed_${System.currentTimeMillis()}.png")
                val fOut = FileOutputStream(file)
                resultBitmap.compress(Bitmap.CompressFormat.PNG, 100, fOut)
                fOut.close()

                val newUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                savedStateHandle["uiState"] = uiState.value.copy(processedImageUri = newUri, isLoading = false)
            } catch (e: Exception) {
                e.printStackTrace()
                setLoading(false)
            }
        }
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
            withContext(Dispatchers.IO) {
                saveBitmapToGallery(getApplication(), bitmap)
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(getApplication(), "Image saved to gallery", Toast.LENGTH_SHORT).show()
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

    fun onCycleBlendMode() {
        val currentMode = uiState.value.blendMode
        val nextMode = when (currentMode) {
            BlendMode.SrcOver -> BlendMode.Multiply
            BlendMode.Multiply -> BlendMode.Screen
            BlendMode.Screen -> BlendMode.Overlay
            BlendMode.Overlay -> BlendMode.Darken
            BlendMode.Darken -> BlendMode.Lighten
            BlendMode.Lighten -> BlendMode.SrcOver
            else -> BlendMode.SrcOver
        }
        Toast.makeText(getApplication(), "Blend Mode: ${nextMode.toString()}", Toast.LENGTH_SHORT).show()
        savedStateHandle["uiState"] = uiState.value.copy(blendMode = nextMode)
    }

    fun saveProject(projectName: String) {
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
                    scale = uiState.value.scale,
                    rotationZ = uiState.value.rotationZ,
                    rotationX = uiState.value.rotationX,
                    rotationY = uiState.value.rotationY,
                    offset = uiState.value.offset,
                    blendMode = uiState.value.blendMode,
                    fingerprint = uiState.value.fingerprintJson?.let { Json.decodeFromString(it) },
                    drawingPaths = uiState.value.drawingPaths
                )
                projectManager.saveProject(projectName, projectData)
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Project '$projectName' saved", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error saving project", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Failed to save project", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun loadProject(projectName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                projectManager.loadProject(projectName)?.let { projectData ->
                    savedStateHandle["uiState"] = uiState.value.copy(
                        backgroundImageUri = projectData.backgroundImageUri,
                        overlayImageUri = projectData.overlayImageUri,
                        opacity = projectData.opacity,
                        contrast = projectData.contrast,
                        saturation = projectData.saturation,
                        colorBalanceR = projectData.colorBalanceR,
                        colorBalanceG = projectData.colorBalanceG,
                        colorBalanceB = projectData.colorBalanceB,
                        scale = projectData.scale,
                        rotationZ = projectData.rotationZ,
                        rotationX = projectData.rotationX,
                        rotationY = projectData.rotationY,
                        offset = projectData.offset,
                        blendMode = projectData.blendMode,
                        fingerprintJson = projectData.fingerprint?.let { Json.encodeToString(Fingerprint.serializer(), it) },
                        drawingPaths = projectData.drawingPaths
                    )
                    projectData.overlayImageUri?.let { uri ->
                        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            ImageDecoder.decodeBitmap(ImageDecoder.createSource(getApplication<Application>().contentResolver, uri))
                        } else {
                            @Suppress("DEPRECATION")
                            val source = ImageDecoder.createSource(getApplication<Application>().contentResolver, uri)
                            ImageDecoder.decodeBitmap(source)
                        }
                        val session = arCoreManager.session ?: return@launch
                        val config = session.config
                        val database = AugmentedImageDatabase(session)
                        database.addImage("target", bitmap)
                        config.augmentedImageDatabase = database
                        session.configure(config)
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(getApplication(), "Project '$projectName' loaded", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error loading project", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Failed to load project", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun getProjectList(): List<String> {
        return projectManager.getProjectList()
    }

    fun deleteProject(projectName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            projectManager.deleteProject(projectName)
            withContext(Dispatchers.Main) {
                Toast.makeText(getApplication(), "Project '$projectName' deleted", Toast.LENGTH_SHORT).show()
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
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                Toast.makeText(getApplication(), "Creating target...", Toast.LENGTH_SHORT).show()
            }
            try {
                onTargetCreationStateChanged(TargetCreationState.CREATING)
                val bitmap = uiState.value.overlayImageUri?.let { uri ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        ImageDecoder.decodeBitmap(ImageDecoder.createSource(getApplication<Application>().contentResolver, uri))
                    } else {
                        @Suppress("DEPRECATION")
                        val source = ImageDecoder.createSource(getApplication<Application>().contentResolver, uri)
                        ImageDecoder.decodeBitmap(source)
                    }
                }

                if (bitmap != null) {
                    val session = arCoreManager.session ?: return@launch
                    val config = session.config
                    val grayMat = Mat()
                    Utils.bitmapToMat(bitmap, grayMat)
                    Imgproc.cvtColor(grayMat, grayMat, Imgproc.COLOR_BGR2GRAY)
                    val orb = ORB.create()
                    val keypoints = MatOfKeyPoint()
                    val descriptors = Mat()
                    orb.detectAndCompute(grayMat, Mat(), keypoints, descriptors)

                    val fingerprint = Fingerprint(keypoints.toList(), descriptors)
                    val fingerprintJson = Json.encodeToString(Fingerprint.serializer(), fingerprint)

                    val database = AugmentedImageDatabase(session)
                    database.addImage("target", bitmap)
                    config.augmentedImageDatabase = database
                    session.configure(config)

                    savedStateHandle["uiState"] = uiState.value.copy(fingerprintJson = fingerprintJson)
                    onTargetCreationStateChanged(TargetCreationState.SUCCESS)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(getApplication(), "Target created successfully", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    onTargetCreationStateChanged(TargetCreationState.ERROR)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(getApplication(), "Failed to create target: No image selected", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                onTargetCreationStateChanged(TargetCreationState.ERROR)
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Failed to create target: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun onNewProject() {
        savedStateHandle["uiState"] = UiState()
    }

    fun onCurvesPointsChanged(points: List<Offset>) {
        updateState(uiState.value.copy(curvesPoints = points))
    }

    fun onUndoClicked() {
        if (undoStack.isNotEmpty()) {
            val lastState = undoStack.removeAt(undoStack.lastIndex)
            redoStack.add(uiState.value)
            savedStateHandle["uiState"] = lastState.copy(canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty())
        }
    }

    fun onRedoClicked() {
        if (redoStack.isNotEmpty()) {
            val nextState = redoStack.removeAt(redoStack.lastIndex)
            undoStack.add(uiState.value)
            savedStateHandle["uiState"] = nextState.copy(canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty())
        }
    }

    fun onGestureStart() {
        // Overwrite the last state in the undo stack
        if (undoStack.isNotEmpty()) {
            undoStack[undoStack.lastIndex] = uiState.value
        } else {
            undoStack.add(uiState.value)
        }
        redoStack.clear()
    }

    fun onGestureEnd() {
        // No action needed here as the state is already saved at the start
    }

    private fun updateState(newState: UiState, isUndoable: Boolean = true) {
        if (isUndoable) {
            undoStack.add(uiState.value)
            redoStack.clear()
        }
        savedStateHandle["uiState"] = newState.copy(
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty()
        )
    }

    fun onMarkProgressToggled() {
        savedStateHandle["uiState"] = uiState.value.copy(isMarkingProgress = !uiState.value.isMarkingProgress)
    }

    fun onDrawingPathUpdate(points: List<Pair<Float, Float>>) {
        val newPaths = uiState.value.drawingPaths + listOf(points)
        savedStateHandle["uiState"] = uiState.value.copy(drawingPaths = newPaths)
        updateProgress(points)
    }

    private fun updateProgress(newPath: List<Pair<Float, Float>>) {
        viewModelScope.launch {
            val overlayImageUri = uiState.value.overlayImageUri ?: return@launch
            val (width, height) = getBitmapDimensions(overlayImageUri)
            if (coloredPixelsBitmap == null) {
                coloredPixelsBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }
            val path = androidx.compose.ui.graphics.Path()
            if (newPath.isNotEmpty()) {
                path.moveTo(newPath[0].first, newPath[0].second)
                for (i in 1 until newPath.size) {
                    path.lineTo(newPath[i].first, newPath[i].second)
                }
            }

            val newColoredPixels = coloredPixelsBitmap?.let {
                com.hereliesaz.graffitixr.utils.calculateProgress(listOf(path), it)
            } ?: 0
            totalColoredPixels += newColoredPixels
            val progress = (totalColoredPixels.toFloat() / (width * height).toFloat()) * 100
            savedStateHandle["uiState"] = uiState.value.copy(progressPercentage = progress)
        }
    }
    private suspend fun getBitmapDimensions(uri: Uri): Pair<Int, Int> {
        return withContext(Dispatchers.IO) {
            try {
                val context = getApplication<Application>().applicationContext
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                val bitmap = ImageDecoder.decodeBitmap(source)
                Pair(bitmap.width, bitmap.height)
            } catch (e: Exception) {
                e.printStackTrace()
                Pair(0, 0)
            }
        }
    }
}
