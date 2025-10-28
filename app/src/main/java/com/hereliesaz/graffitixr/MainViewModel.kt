package com.hereliesaz.graffitixr

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.geometry.Rect
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Session
import com.hereliesaz.graffitixr.data.Fingerprint
import com.hereliesaz.graffitixr.data.ProjectData
import com.hereliesaz.graffitixr.utils.OnboardingManager
import com.hereliesaz.graffitixr.utils.ProjectManager
import com.hereliesaz.graffitixr.utils.convertToLineDrawing
import com.hereliesaz.graffitixr.utils.saveBitmapToGallery
import com.slowmac.autobackgroundremover.removeBackground
import java.io.File
import java.io.FileOutputStream
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
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfKeyPoint
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc

sealed class CaptureEvent {
    object RequestCapture : CaptureEvent()
    object RequestTargetCapture : CaptureEvent()
    object RequestCaptureForRefinement : CaptureEvent()
}

sealed class TapFeedback {
    data class Success(val position: Offset) : TapFeedback()
    data class Failure(val position: Offset) : TapFeedback()
}

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

    init {
        val completedModes = onboardingManager.getCompletedModes()
        updateState(uiState.value.copy(completedOnboardingModes = completedModes), isUndoable = false)
    }

    fun showTapFeedback(position: Offset, isSuccess: Boolean) {
        viewModelScope.launch {
            _tapFeedback.value = if (isSuccess) TapFeedback.Success(position) else TapFeedback.Failure(position)
            delay(500)
            _tapFeedback.value = null
        }
    }

    @Suppress("DEPRECATION")
    private fun getBitmapFromUri(uri: Uri): Bitmap? {
        val context = getApplication<Application>().applicationContext
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source)
            } else {
                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun onRemoveBackgroundClicked() {
        viewModelScope.launch {
            setLoading(true)
            val uri = uiState.value.overlayImageUri
            if (uri != null) {
                try {
                    val context = getApplication<Application>().applicationContext
                    val bitmap = getBitmapFromUri(uri) ?: return@launch

                    val resultBitmap = bitmap.removeBackground(context)

                    val cachePath = File(context.cacheDir, "images")
                    cachePath.mkdirs()
                    val file = File(cachePath, "background_removed_${System.currentTimeMillis()}.png")
                    val fOut = FileOutputStream(file)
                    resultBitmap.compress(Bitmap.CompressFormat.PNG, 100, fOut)
                    fOut.close()

                    val newUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                    updateState(uiState.value.copy(backgroundRemovedImageUri = newUri, isLoading = false))
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
                val bitmap = getBitmapFromUri(uri)?.copy(Bitmap.Config.ARGB_8888, true) ?: return@launch

                val lineDrawingBitmap = convertToLineDrawing(bitmap)

                val cachePath = File(context.cacheDir, "images")
                cachePath.mkdirs()
                val file = File(cachePath, "line_drawing_${System.currentTimeMillis()}.png")
                val fOut = FileOutputStream(file)
                lineDrawingBitmap.compress(Bitmap.CompressFormat.PNG, 100, fOut)
                fOut.close()

                val newUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                updateState(uiState.value.copy(overlayImageUri = newUri, isLoading = false))
            } else {
                setLoading(false)
            }
        }
    }

    private fun setLoading(isLoading: Boolean) {
        updateState(uiState.value.copy(isLoading = isLoading), isUndoable = false)
    }

    fun onBackgroundImageSelected(uri: Uri) {
        updateState(uiState.value.copy(backgroundImageUri = uri))
    }

    fun onOverlayImageSelected(uri: Uri) {
        val showHint = !onboardingManager.hasSeenDoubleTapHint()
        updateState(uiState.value.copy(
            overlayImageUri = uri,
            backgroundRemovedImageUri = null,
            showDoubleTapHint = showHint
        ))
        viewModelScope.launch {
            val (width, height) = getBitmapDimensions(uri)
            coloredPixelsBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            totalColoredPixels = 0
        }
    }

    fun onOpacityChanged(opacity: Float) {
        updateState(uiState.value.copy(opacity = opacity), isUndoable = false)
    }

    fun onContrastChanged(contrast: Float) {
        updateState(uiState.value.copy(contrast = contrast), isUndoable = false)
    }

    fun onSaturationChanged(saturation: Float) {
        updateState(uiState.value.copy(saturation = saturation), isUndoable = false)
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

    fun onOnboardingComplete(mode: EditorMode, dontShowAgain: Boolean) {
        if (dontShowAgain) {
            onboardingManager.completeMode(mode)
            val updatedModes = onboardingManager.getCompletedModes()
            updateState(uiState.value.copy(completedOnboardingModes = updatedModes))
        }
    }

    fun onDoubleTapHintDismissed() {
        onboardingManager.setDoubleTapHintSeen()
        updateState(uiState.value.copy(showDoubleTapHint = false))
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
                val bitmap = getBitmapFromUri(uri) ?: return@launch

                val resultBitmap = com.hereliesaz.graffitixr.utils.applyCurves(bitmap, points)

                val cachePath = File(context.cacheDir, "images")
                cachePath.mkdirs()
                val file = File(cachePath, "curves_processed_${System.currentTimeMillis()}.png")
                val fOut = FileOutputStream(file)
                resultBitmap.compress(Bitmap.CompressFormat.PNG, 100, fOut)
                fOut.close()

                val newUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                updateState(uiState.value.copy(processedImageUri = newUri, isLoading = false))
            } catch (e: Exception) {
                e.printStackTrace()
                setLoading(false)
            }
        }
    }


    fun onCycleRotationAxis() {
        val currentAxis = uiState.value.activeRotationAxis
        val nextAxis = when (currentAxis) {
            RotationAxis.X -> RotationAxis.Y
            RotationAxis.Y -> RotationAxis.Z
            RotationAxis.Z -> RotationAxis.X
        }
        Toast.makeText(getApplication(), "Rotating around ${nextAxis.name} axis", Toast.LENGTH_SHORT).show()
        updateState(uiState.value.copy(
            activeRotationAxis = nextAxis,
            showRotationAxisFeedback = true
        ))
    }

    fun onRotationXChanged(delta: Float) {
        updateState(uiState.value.copy(rotationX = uiState.value.rotationX + delta), isUndoable = false)
    }

    fun onRotationYChanged(delta: Float) {
        updateState(uiState.value.copy(rotationY = uiState.value.rotationY + delta), isUndoable = false)
    }

    fun onFeedbackShown() {
        viewModelScope.launch {
            delay(1000) // Keep feedback visible for 1 second
            updateState(uiState.value.copy(showRotationAxisFeedback = false))
        }
    }

    fun onSaveClicked() {
        viewModelScope.launch {
            _captureEvent.emit(CaptureEvent.RequestCapture)
        }
    }

    fun onCaptureForRefinementClicked() {
        viewModelScope.launch {
            _captureEvent.emit(CaptureEvent.RequestCaptureForRefinement)
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

    fun setRefinementBitmap(bitmap: Bitmap) {
        val context = getApplication<Application>().applicationContext
        val cachePath = File(context.cacheDir, "images")
        cachePath.mkdirs()
        val file = File(cachePath, "refinement_bitmap.png")
        val fOut = FileOutputStream(file)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, fOut)
        fOut.close()
        val newUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        updateState(uiState.value.copy(refinementImageUri = newUri))
    }

    fun onRefinementCancel() {
        updateState(uiState.value.copy(refinementImageUri = null))
    }

    fun onRefinementConfirm(cropRect: Rect) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val uri = uiState.value.refinementImageUri ?: return@launch
                val context = getApplication<Application>().applicationContext
                val bitmap = getBitmapFromUri(uri) ?: return@launch

                val croppedBitmap = Bitmap.createBitmap(
                    bitmap,
                    cropRect.left.toInt(),
                    cropRect.top.toInt(),
                    cropRect.width.toInt(),
                    cropRect.height.toInt()
                )

                val session = arCoreManager.session ?: return@launch
                val config = session.config
                val grayMat = Mat()
                Utils.bitmapToMat(croppedBitmap, grayMat)
                Imgproc.cvtColor(grayMat, grayMat, Imgproc.COLOR_BGR2GRAY)
                val orb = ORB.create()
                val keypoints = MatOfKeyPoint()
                val descriptors = Mat()
                orb.detectAndCompute(grayMat, Mat(), keypoints, descriptors)

                val fingerprint = Fingerprint(keypoints.toList(), descriptors)
                val fingerprintJson = Json.encodeToString(Fingerprint.serializer(), fingerprint)

                val database = AugmentedImageDatabase(session)
                database.addImage("target", croppedBitmap)
                config.augmentedImageDatabase = database
                session.configure(config)

                updateState(uiState.value.copy(fingerprintJson = fingerprintJson, refinementImageUri = null))
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Target created successfully", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Failed to create target: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }


    fun onColorBalanceRChanged(value: Float) {
        updateState(uiState.value.copy(colorBalanceR = value), isUndoable = false)
    }

    fun onColorBalanceGChanged(value: Float) {
        updateState(uiState.value.copy(colorBalanceG = value), isUndoable = false)
    }

    fun onColorBalanceBChanged(value: Float) {
        updateState(uiState.value.copy(colorBalanceB = value), isUndoable = false)
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
        updateState(uiState.value.copy(blendMode = nextMode))
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
                    updateState(uiState.value.copy(
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
                    ))
                    projectData.overlayImageUri?.let { uri ->
                        val bitmap = getBitmapFromUri(uri) ?: return@let
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
        updateState(uiState.value.copy(arState = newState), isUndoable = false)
    }

    fun onTargetCreationStateChanged(newState: TargetCreationState) {
        updateState(uiState.value.copy(targetCreationState = newState), isUndoable = false)
    }

    fun setArTarget(bitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                Toast.makeText(getApplication(), "Creating target...", Toast.LENGTH_SHORT).show()
            }
            try {
                onTargetCreationStateChanged(TargetCreationState.CREATING)
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

                updateState(uiState.value.copy(fingerprintJson = fingerprintJson))
                onTargetCreationStateChanged(TargetCreationState.SUCCESS)
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Target created successfully", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                onTargetCreationStateChanged(TargetCreationState.ERROR)
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Failed to create target: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun onCreateTargetClicked() {
        viewModelScope.launch {
            _captureEvent.emit(CaptureEvent.RequestTargetCapture)
        }
    }

    fun onNewProject() {
        updateState(UiState())
    }

    fun onCurvesPointsChanged(points: List<Offset>) {
        updateState(uiState.value.copy(curvesPoints = points), isUndoable = false)
    }

    fun onUndoClicked() {
        if (undoStack.isNotEmpty()) {
            val lastState = undoStack.removeAt(undoStack.lastIndex)
            redoStack.add(uiState.value)
            updateState(lastState, isUndoable = false)
        }
    }

    fun onRedoClicked() {
        if (redoStack.isNotEmpty()) {
            val nextState = redoStack.removeAt(redoStack.lastIndex)
            undoStack.add(uiState.value)
            updateState(nextState, isUndoable = false)
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
        updateState(uiState.value.copy(isMarkingProgress = !uiState.value.isMarkingProgress))
    }

    fun onDrawingPathFinished(points: List<Pair<Float, Float>>) {
        val newPaths = uiState.value.drawingPaths + listOf(points)
        updateState(uiState.value.copy(drawingPaths = newPaths))
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
            updateState(uiState.value.copy(progressPercentage = progress), isUndoable = false)
        }
    }
    private suspend fun getBitmapDimensions(uri: Uri): Pair<Int, Int> {
        return withContext(Dispatchers.IO) {
            getBitmapFromUri(uri)?.let {
                Pair(it.width, it.height)
            } ?: Pair(0, 0)
        }
    }
}
