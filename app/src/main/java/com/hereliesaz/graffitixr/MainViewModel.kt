package com.hereliesaz.graffitixr

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.data.Fingerprint
import com.hereliesaz.graffitixr.data.ProjectData
import com.hereliesaz.graffitixr.data.RefinementPath
import com.hereliesaz.graffitixr.utils.BitmapUtils
import com.hereliesaz.graffitixr.utils.OnboardingManager
import com.hereliesaz.graffitixr.utils.ProjectManager
import com.hereliesaz.graffitixr.utils.applyMaskToBitmap
import com.hereliesaz.graffitixr.utils.calculateBlurMetric
import com.hereliesaz.graffitixr.utils.convertToLineDrawing
import com.hereliesaz.graffitixr.utils.detectFeaturesWithMask
import com.hereliesaz.graffitixr.utils.estimateFeatureRichness
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

class MainViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    private val onboardingManager = OnboardingManager(application)
    private val projectManager = ProjectManager(application)

    private val undoStack = mutableListOf<UiState>()
    private val redoStack = mutableListOf<UiState>()

    val uiState: StateFlow<UiState> = savedStateHandle.getStateFlow("uiState", UiState())

    private val _captureEvent = MutableSharedFlow<CaptureEvent>()
    val captureEvent = _captureEvent.asSharedFlow()

    private val _tapFeedback = MutableStateFlow<TapFeedback?>(null)
    val tapFeedback = _tapFeedback.asStateFlow()

    var arRenderer: ArRenderer? = null

    init {
        val completedModes = onboardingManager.getCompletedModes()
        updateState(uiState.value.copy(
            completedOnboardingModes = completedModes,
            showOnboardingDialogForMode = if (!completedModes.contains(uiState.value.editorMode)) uiState.value.editorMode else null
        ), isUndoable = false)
    }

    // --- AR Target Creation Logic ---

    fun onCreateTargetClicked() {
        updateState(uiState.value.copy(
            isCapturingTarget = true,
            captureStep = CaptureStep.FRONT,
            capturedTargetImages = emptyList(),
            qualityWarning = null,
            refinementPaths = emptyList()
        ), isUndoable = false)
    }

    fun onCaptureShutterClicked() {
        arRenderer?.triggerCapture()
    }

    fun onCancelCaptureClicked() {
        updateState(uiState.value.copy(
            isCapturingTarget = false,
            capturedTargetImages = emptyList()
        ), isUndoable = false)
    }

    fun onFrameCaptured(bitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.IO) {
            val blurScore = calculateBlurMetric(bitmap)
            val features = estimateFeatureRichness(bitmap)

            if (blurScore < 100.0) {
                withContext(Dispatchers.Main) {
                    updateState(uiState.value.copy(qualityWarning = "Image too blurry. Hold steady!"), isUndoable = false)
                }
                return@launch
            }

            if (features < 200) {
                withContext(Dispatchers.Main) {
                    updateState(uiState.value.copy(qualityWarning = "Not enough detail."), isUndoable = false)
                }
                return@launch
            }

            val currentImages = uiState.value.capturedTargetImages + bitmap
            val nextStep = when (uiState.value.captureStep) {
                CaptureStep.FRONT -> CaptureStep.LEFT
                CaptureStep.LEFT -> CaptureStep.RIGHT
                CaptureStep.RIGHT -> CaptureStep.UP
                CaptureStep.UP -> CaptureStep.DOWN
                CaptureStep.DOWN -> CaptureStep.REVIEW
                else -> CaptureStep.REVIEW
            }

            withContext(Dispatchers.Main) {
                updateState(uiState.value.copy(
                    captureStep = nextStep,
                    capturedTargetImages = currentImages,
                    qualityWarning = null
                ), isUndoable = false)

                if (nextStep == CaptureStep.REVIEW) {
                    startRefinement()
                }
            }
        }
    }

    private fun startRefinement() {
        viewModelScope.launch(Dispatchers.IO) {
            val primaryBitmap = uiState.value.capturedTargetImages.firstOrNull() ?: return@launch
            val initialKeypoints = detectFeaturesWithMask(primaryBitmap, emptyList())

            withContext(Dispatchers.Main) {
                updateState(uiState.value.copy(
                    detectedKeypoints = initialKeypoints,
                    refinementPaths = emptyList()
                ), isUndoable = false)
            }
        }
    }

    fun onRefinementPathAdded(path: RefinementPath) {
        val newPaths = uiState.value.refinementPaths + path
        updateState(uiState.value.copy(refinementPaths = newPaths), isUndoable = false)

        viewModelScope.launch(Dispatchers.IO) {
            val primaryBitmap = uiState.value.capturedTargetImages.firstOrNull() ?: return@launch
            val updatedKeypoints = detectFeaturesWithMask(primaryBitmap, newPaths)

            withContext(Dispatchers.Main) {
                updateState(uiState.value.copy(detectedKeypoints = updatedKeypoints), isUndoable = false)
            }
        }
    }

    fun onRefinementModeChanged(isEraser: Boolean) {
        updateState(uiState.value.copy(isRefinementEraser = isEraser), isUndoable = false)
    }

    fun onConfirmTargetCreation() {
        finalizeTargetCreation()
    }

    private fun finalizeTargetCreation() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. Apply Refinement Mask to ALL images
                val rawImages = uiState.value.capturedTargetImages
                val paths = uiState.value.refinementPaths

                val maskedImages = rawImages.map { bitmap ->
                    applyMaskToBitmap(bitmap, paths)
                }

                // 2. Generate Fingerprint from PRIMARY masked image
                val primaryBitmap = maskedImages.first()
                val grayMat = Mat()
                Utils.bitmapToMat(primaryBitmap, grayMat)
                Imgproc.cvtColor(grayMat, grayMat, Imgproc.COLOR_BGR2GRAY)

                val orb = ORB.create()
                val keypoints = MatOfKeyPoint()
                val descriptors = Mat()
                orb.detectAndCompute(grayMat, Mat(), keypoints, descriptors)

                val fingerprint = Fingerprint(keypoints.toList(), descriptors)
                val fingerprintJson = Json.encodeToString(Fingerprint.serializer(), fingerprint)

                // 3. Update State & AR
                withContext(Dispatchers.Main) {
                    arRenderer?.setAugmentedImageDatabase(maskedImages)

                    updateState(
                        uiState.value.copy(
                            fingerprintJson = fingerprintJson,
                            isCapturingTarget = false,
                            capturedTargetImages = maskedImages,
                            isArTargetCreated = true,
                            arState = ArState.LOCKED,
                            captureStep = CaptureStep.FRONT,
                            refinementPaths = emptyList()
                        )
                    )
                    Toast.makeText(getApplication(), "Target finalized and locked!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("TargetCreation", "Finalize failed", e)
                withContext(Dispatchers.Main) {
                    updateState(uiState.value.copy(isCapturingTarget = false, qualityWarning = "Error creating target."))
                }
            }
        }
    }

    // --- Project Persistence ---

    fun saveProject(projectName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val savedTargetUris = projectManager.saveTargetImages(projectName, uiState.value.capturedTargetImages)

                val projectData = ProjectData(
                    backgroundImageUri = uiState.value.backgroundImageUri,
                    overlayImageUri = uiState.value.overlayImageUri,
                    targetImageUris = savedTargetUris,
                    refinementPaths = uiState.value.refinementPaths,
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
                    val loadedBitmaps = projectManager.loadTargetBitmaps(projectData.targetImageUris)

                    withContext(Dispatchers.Main) {
                        updateState(uiState.value.copy(
                            backgroundImageUri = projectData.backgroundImageUri,
                            overlayImageUri = projectData.overlayImageUri,
                            capturedTargetImages = loadedBitmaps,
                            refinementPaths = projectData.refinementPaths,
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
                            drawingPaths = projectData.drawingPaths,
                            isArTargetCreated = loadedBitmaps.isNotEmpty(),
                            arState = if (loadedBitmaps.isNotEmpty()) ArState.LOCKED else ArState.SEARCHING
                        ))

                        if (loadedBitmaps.isNotEmpty()) {
                            arRenderer?.setAugmentedImageDatabase(loadedBitmaps)
                        }

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

    fun getProjectList(): List<String> = projectManager.getProjectList()

    fun deleteProject(projectName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            projectManager.deleteProject(projectName)
            withContext(Dispatchers.Main) {
                Toast.makeText(getApplication(), "Project '$projectName' deleted", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun onNewProject() {
        updateState(UiState(), isUndoable = false)
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
        if (undoStack.isNotEmpty()) {
            undoStack[undoStack.lastIndex] = uiState.value
        } else {
            undoStack.add(uiState.value)
        }
        redoStack.clear()
    }

    fun onGestureEnd() { }

    private fun updateState(newState: UiState, isUndoable: Boolean = true) {
        val currentState = savedStateHandle.get<UiState>("uiState") ?: UiState()
        if (isUndoable) {
            undoStack.add(currentState)
            if (undoStack.size > MAX_UNDO_STACK_SIZE) {
                undoStack.removeAt(0)
            }
            redoStack.clear()
        }
        savedStateHandle["uiState"] = newState.copy(
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty()
        )
    }

    // --- Method that caused Unresolved Reference Error ---
    fun setTouchLocked(locked: Boolean) {
        updateState(uiState.value.copy(isTouchLocked = locked), isUndoable = false)
    }

    fun onMarkProgressToggled() {
        updateState(uiState.value.copy(isMarkingProgress = !uiState.value.isMarkingProgress))
    }

    fun onDrawingPathFinished(points: List<Pair<Float, Float>>) {
        val newPaths = uiState.value.drawingPaths + listOf(points)
        updateState(uiState.value.copy(drawingPaths = newPaths))
        recalculateProgress()
    }

    private fun recalculateProgress() {
        viewModelScope.launch {
            val overlayImageUri = uiState.value.overlayImageUri ?: return@launch
            val allPaths = uiState.value.drawingPaths

            if (allPaths.isEmpty()) {
                updateState(uiState.value.copy(progressPercentage = 0f), isUndoable = false)
                return@launch
            }

            val (width, height) = BitmapUtils.getBitmapDimensions(getApplication(), overlayImageUri)
            if (width == 0 || height == 0) return@launch

            val progressBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

            val composePaths = allPaths.map { pointList ->
                androidx.compose.ui.graphics.Path().apply {
                    if (pointList.isNotEmpty()) {
                        moveTo(pointList.first().first, pointList.first().second)
                        for (i in 1 until pointList.size) {
                            lineTo(pointList[i].first, pointList[i].second)
                        }
                    }
                }
            }

            val totalColoredPixels = com.hereliesaz.graffitixr.utils.calculateProgress(composePaths, progressBitmap)
            val progress = (totalColoredPixels.toFloat() / (width * height).toFloat()) * 100
            updateState(uiState.value.copy(progressPercentage = progress), isUndoable = false)
        }
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            updateState(uiState.value.copy(isCheckingForUpdate = true, updateStatusMessage = null), isUndoable = false)

            withContext(Dispatchers.IO) {
                try {
                    val url = java.net.URL("https://api.github.com/repos/HereLiesAZ/GraffitiXR/releases")
                    val connection = url.openConnection() as javax.net.ssl.HttpsURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    connection.setRequestProperty("User-Agent", "GraffitiXR-App")

                    if (connection.responseCode == 200) {
                        val reader = java.io.BufferedReader(java.io.InputStreamReader(connection.inputStream))
                        val response = StringBuilder()
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            response.append(line)
                        }
                        reader.close()

                        val json = Json { ignoreUnknownKeys = true }
                        val releases = json.decodeFromString<List<com.hereliesaz.graffitixr.data.GithubRelease>>(response.toString())

                        if (releases.isNotEmpty()) {
                            val latestRelease = releases.firstOrNull()
                            withContext(Dispatchers.Main) {
                                if (latestRelease != null) {
                                    val message = if (latestRelease.tag_name > BuildConfig.VERSION_NAME) {
                                        "New version available: ${latestRelease.tag_name}"
                                    } else {
                                        "You have the latest version."
                                    }
                                    updateState(uiState.value.copy(
                                        isCheckingForUpdate = false,
                                        updateStatusMessage = message,
                                        latestRelease = latestRelease
                                    ), isUndoable = false)
                                } else {
                                    updateState(uiState.value.copy(
                                        isCheckingForUpdate = false,
                                        updateStatusMessage = "No releases found."
                                    ), isUndoable = false)
                                }
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                updateState(uiState.value.copy(isCheckingForUpdate = false, updateStatusMessage = "No releases found."), isUndoable = false)
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            updateState(uiState.value.copy(isCheckingForUpdate = false, updateStatusMessage = "HTTP ${connection.responseCode}"), isUndoable = false)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        updateState(uiState.value.copy(isCheckingForUpdate = false, updateStatusMessage = "Error: ${e.message}"), isUndoable = false)
                    }
                }
            }
        }
    }

    fun installLatestUpdate() {
        val release = uiState.value.latestRelease ?: return
        val asset = release.assets.firstOrNull { it.browser_download_url.endsWith(".apk") } ?: return

        val downloadUrl = asset.browser_download_url
        val fileName = "GraffitiXR-${release.tag_name}.apk"

        try {
            val request = DownloadManager.Request(Uri.parse(downloadUrl))
                .setTitle(fileName)
                .setDescription("Downloading GraffitiXR Update")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

            val downloadManager = getApplication<Application>().getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)

            Toast.makeText(getApplication(), "Downloading update...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(getApplication(), "Failed to start download: ${e.message}", Toast.LENGTH_LONG).show()
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
        updateState(uiState.value.copy(blendMode = nextMode))
    }

    fun onArObjectScaleChanged(scaleFactor: Float) {
        val currentScale = uiState.value.arObjectScale
        val newScale = (currentScale * scaleFactor).coerceIn(0.1f, 10.0f)
        updateState(uiState.value.copy(arObjectScale = newScale), isUndoable = false)
    }

    fun onRotationXChanged(delta: Float) {
        updateState(uiState.value.copy(rotationX = uiState.value.rotationX + delta), isUndoable = false)
    }

    fun onRotationYChanged(delta: Float) {
        updateState(uiState.value.copy(rotationY = uiState.value.rotationY + delta), isUndoable = false)
    }

    fun onRotationZChanged(delta: Float) {
        val currentRotation = uiState.value.rotationZ
        updateState(uiState.value.copy(rotationZ = currentRotation + delta), isUndoable = false)
    }

    fun onScaleChanged(scaleFactor: Float) {
        val currentScale = uiState.value.scale
        updateState(uiState.value.copy(scale = currentScale * scaleFactor), isUndoable = false)
    }

    fun onOffsetChanged(offset: Offset) {
        updateState(uiState.value.copy(offset = uiState.value.offset + offset), isUndoable = false)
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
    }

    fun onEditorModeChanged(mode: EditorMode) {
        val showOnboarding = !uiState.value.completedOnboardingModes.contains(mode)
        updateState(
            uiState.value.copy(
                editorMode = mode,
                showOnboardingDialogForMode = if (showOnboarding) mode else null
            )
        )
    }

    fun onOnboardingComplete(mode: EditorMode, dontShowAgain: Boolean) {
        val updatedState = if (dontShowAgain) {
            onboardingManager.completeMode(mode)
            val updatedModes = onboardingManager.getCompletedModes()
            uiState.value.copy(
                completedOnboardingModes = updatedModes,
                showOnboardingDialogForMode = null
            )
        } else {
            uiState.value.copy(showOnboardingDialogForMode = null)
        }

        if (mode == EditorMode.HELP) {
            updateState(updatedState.copy(editorMode = EditorMode.STATIC))
        } else {
            updateState(updatedState)
        }
    }

    fun onDoubleTapHintDismissed() {
        onboardingManager.setDoubleTapHintSeen()
        updateState(uiState.value.copy(showDoubleTapHint = false))
    }

    fun onFeedbackShown() {
        viewModelScope.launch {
            delay(1000)
            updateState(uiState.value.copy(showRotationAxisFeedback = false))
        }
    }

    fun onCycleRotationAxis() {
        val currentAxis = uiState.value.activeRotationAxis
        val nextAxis = when (currentAxis) {
            RotationAxis.X -> RotationAxis.Y
            RotationAxis.Y -> RotationAxis.Z
            RotationAxis.Z -> RotationAxis.X
        }
        updateState(uiState.value.copy(
            activeRotationAxis = nextAxis,
            showRotationAxisFeedback = true
        ))
    }

    fun onSaveClicked() {
        viewModelScope.launch {
            _captureEvent.emit(CaptureEvent.RequestCapture)
        }
    }

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

    fun onRemoveBackgroundClicked() {
        viewModelScope.launch {
            setLoading(true)
            val uri = uiState.value.overlayImageUri
            if (uri != null) {
                try {
                    val context = getApplication<Application>().applicationContext
                    val bitmap = BitmapUtils.getBitmapFromUri(context, uri) ?: return@launch
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
                val bitmap = BitmapUtils.getBitmapFromUri(context, uri)?.copy(Bitmap.Config.ARGB_8888, true) ?: return@launch
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

    fun setArPlanesDetected(detected: Boolean) {
        if (uiState.value.isArPlanesDetected != detected) {
            updateState(uiState.value.copy(isArPlanesDetected = detected), isUndoable = false)
        }
    }

    fun onArImagePlaced() {
        updateState(uiState.value.copy(arState = ArState.PLACED), isUndoable = false)
    }

    fun onCurvesPointsChanged(points: List<Offset>) {
        updateState(uiState.value.copy(curvesPoints = points), isUndoable = false)
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
                val bitmap = BitmapUtils.getBitmapFromUri(context, uri) ?: return@launch
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

    private fun setLoading(isLoading: Boolean) {
        updateState(uiState.value.copy(isLoading = isLoading), isUndoable = false)
    }

    companion object {
        private const val MAX_UNDO_STACK_SIZE = 50
    }
}