package com.hereliesaz.graffitixr

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
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
import com.hereliesaz.graffitixr.data.GithubRelease
import com.hereliesaz.graffitixr.data.ProjectData
import com.hereliesaz.graffitixr.utils.BitmapUtils
import com.hereliesaz.graffitixr.utils.OnboardingManager
import com.hereliesaz.graffitixr.utils.ProjectManager
import com.hereliesaz.graffitixr.utils.convertToLineDrawing
import com.hereliesaz.graffitixr.utils.saveBitmapToGallery
import com.hereliesaz.graffitixr.utils.BackgroundRemover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume
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

sealed class FeedbackEvent {
    object VibrateSingle : FeedbackEvent()
    object VibrateDouble : FeedbackEvent()
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

    private val _feedbackEvent = MutableSharedFlow<FeedbackEvent>()
    val feedbackEvent = _feedbackEvent.asSharedFlow()

    private val _tapFeedback = MutableStateFlow<TapFeedback?>(null)
    val tapFeedback = _tapFeedback.asStateFlow()

    // Reference to the active AR Renderer (injected by ArView)
    var arRenderer: ArRenderer? = null

    init {
        val completedModes = onboardingManager.getCompletedModes()
        updateState(uiState.value.copy(
            completedOnboardingModes = completedModes,
            showOnboardingDialogForMode = if (!completedModes.contains(uiState.value.editorMode)) uiState.value.editorMode else null
        ), isUndoable = false)

        // Register the broadcast receiver
        val receiver = ApkInstallReceiver()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            application.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)
        } else {
            application.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    // --- AR Target Creation Logic ---

    fun onCaptureShutterClicked() {
        viewModelScope.launch {
            _captureEvent.emit(CaptureEvent.RequestCapture)
        }
    }

    fun onCancelCaptureClicked() {
        updateState(uiState.value.copy(isCapturingTarget = false, targetCreationState = TargetCreationState.IDLE))
    }

    fun onRefinementPathAdded(path: com.hereliesaz.graffitixr.data.RefinementPath) {
        val currentPaths = uiState.value.refinementPaths
        updateState(uiState.value.copy(refinementPaths = currentPaths + path), isUndoable = false)
    }

    fun onRefinementModeChanged(isEraser: Boolean) {
        updateState(uiState.value.copy(isRefinementEraser = isEraser), isUndoable = false)
    }

    fun onConfirmTargetCreation() {
        updateState(uiState.value.copy(
            isCapturingTarget = false,
            targetCreationState = TargetCreationState.SUCCESS,
            isArTargetCreated = true,
            arState = ArState.LOCKED
        ))
    }

    fun onCreateTargetClicked() {
        updateState(uiState.value.copy(isCapturingTarget = true), isUndoable = false)
        arRenderer?.triggerCapture()
    }

    fun onFrameCaptured(bitmap: Bitmap) {
        viewModelScope.launch {
            delay(400)
            withContext(Dispatchers.IO) {
                try {
                    val grayMat = Mat()
                    Utils.bitmapToMat(bitmap, grayMat)
                    Imgproc.cvtColor(grayMat, grayMat, Imgproc.COLOR_BGR2GRAY)

                    val orb = ORB.create()
                    val keypoints = MatOfKeyPoint()
                    val descriptors = Mat()
                    orb.detectAndCompute(grayMat, Mat(), keypoints, descriptors)

                    val fingerprint = Fingerprint(keypoints.toList(), descriptors)
                    val fingerprintJson = Json.encodeToString(Fingerprint.serializer(), fingerprint)

                    withContext(Dispatchers.Main) {
                        arRenderer?.setAugmentedImageDatabase(listOf(bitmap))
                        updateState(
                            uiState.value.copy(
                                fingerprintJson = fingerprintJson,
                                isCapturingTarget = false,
                                isArTargetCreated = true,
                                arState = ArState.LOCKED
                            )
                        )
                        Toast.makeText(getApplication(), "Target created successfully", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        updateState(uiState.value.copy(isCapturingTarget = false))
                        Toast.makeText(getApplication(), "Failed to create target: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    fun exportProjectToUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            setLoading(true)
            try {
                // Save target images to cache and get URIs
                val savedTargetUris = uiState.value.capturedTargetImages.mapNotNull { bitmap ->
                    saveBitmapToCache(bitmap)
                }

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
                    drawingPaths = uiState.value.drawingPaths,
                    progressPercentage = uiState.value.progressPercentage,
                    evolutionImageUris = uiState.value.evolutionCaptureUris,
                    gpsData = getGpsData(),
                    sensorData = getSensorData()
                )

                projectManager.exportProjectToZip(uri, projectData)

                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Project saved successfully", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error exporting project", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Failed to save project", Toast.LENGTH_SHORT).show()
                }
            } finally {
                setLoading(false)
            }
        }
    }

    // --- Logic & Transforms ---

    fun onCycleRotationAxis() {
        val currentAxis = uiState.value.activeRotationAxis
        val nextAxis = when (currentAxis) {
            RotationAxis.X -> RotationAxis.Y
            RotationAxis.Y -> RotationAxis.Z
            RotationAxis.Z -> RotationAxis.X
        }

        // Update state and show Toast
        updateState(uiState.value.copy(
            activeRotationAxis = nextAxis,
            showRotationAxisFeedback = true
        ))

        Toast.makeText(getApplication(), "Rotation Axis: ${nextAxis.name}", Toast.LENGTH_SHORT).show()
    }

    fun onArObjectScaleChanged(scaleFactor: Float) {
        val currentScale = uiState.value.arObjectScale
        // Uniform scaling: new scale is previous scale * zoom factor
        // We limit min/max scale to prevent objects disappearing or becoming massive
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

    fun onFeedbackShown() {
        viewModelScope.launch {
            delay(1000)
            updateState(uiState.value.copy(showRotationAxisFeedback = false))
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

    // --- Core State Logic (Unchanged from previous V3) ---

    fun onProgressUpdate(progress: Float, bitmap: Bitmap? = null) {
        val currentProgress = uiState.value.progressPercentage
        val isSignificant = Math.abs(progress - currentProgress) > 5.0f

        if (isSignificant && bitmap != null) {
            viewModelScope.launch(Dispatchers.IO) {
                val uri = saveBitmapToCache(bitmap, "evolution")
                if (uri != null) {
                    withContext(Dispatchers.Main) {
                        val currentUris = uiState.value.evolutionCaptureUris
                        updateState(uiState.value.copy(
                            evolutionCaptureUris = currentUris + listOf(uri)
                        ), isUndoable = false)
                    }
                }
            }
        }

        updateState(uiState.value.copy(progressPercentage = progress), isUndoable = false)
    }

    fun setTouchLocked(locked: Boolean) {
        updateState(uiState.value.copy(isTouchLocked = locked), isUndoable = false)
    }

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
            val uri = uiState.value.originalOverlayImageUri ?: uiState.value.overlayImageUri
            if (uri != null) {
                try {
                    val context = getApplication<Application>().applicationContext
                    val bitmap = BitmapUtils.getBitmapFromUri(context, uri) ?: return@launch
                    val resultBitmap = BackgroundRemover.removeBackground(bitmap)

                    if (resultBitmap != null) {
                        val cachePath = File(context.cacheDir, "images")
                        cachePath.mkdirs()
                        val file = File(cachePath, "background_removed_${System.currentTimeMillis()}.png")
                        val fOut = FileOutputStream(file)
                        resultBitmap.compress(Bitmap.CompressFormat.PNG, 100, fOut)
                        fOut.close()
                        val newUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                        updateState(uiState.value.copy(
                            overlayImageUri = newUri,
                            backgroundRemovedImageUri = newUri,
                            isLoading = false
                        ))
                    } else {
                        setLoading(false)
                        Toast.makeText(getApplication(), "Background removal failed", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    setLoading(false)
                }
            } else {
                setLoading(false)
            }
        }
    }

    fun onBrightnessChanged(brightness: Float) {
        updateState(uiState.value.copy(brightness = brightness), isUndoable = false)
    }

    fun onLineDrawingClicked() {
        viewModelScope.launch {
            setLoading(true)

            val isCurrentlyLineDrawing = uiState.value.isLineDrawing
            val nextState = !isCurrentlyLineDrawing

            if (!nextState) {
                // Turn OFF line drawing, restore prioritized image
                val restoreUri = uiState.value.backgroundRemovedImageUri ?: uiState.value.originalOverlayImageUri ?: uiState.value.overlayImageUri

                if (restoreUri != null) {
                    updateState(uiState.value.copy(
                        overlayImageUri = restoreUri,
                        isLineDrawing = false,
                        isLoading = false
                    ))
                } else {
                    updateState(uiState.value.copy(isLineDrawing = false, isLoading = false))
                }
                return@launch
            }

            // Turn ON line drawing
            val uri = uiState.value.backgroundRemovedImageUri ?: uiState.value.originalOverlayImageUri ?: uiState.value.overlayImageUri

            if (uri != null) {
                val context = getApplication<Application>().applicationContext
                val bitmap = BitmapUtils.getBitmapFromUri(context, uri)?.copy(Bitmap.Config.ARGB_8888, true) ?: return@launch

                val lineDrawingBitmap = convertToLineDrawing(bitmap, isWhite = true)

                val cachePath = File(context.cacheDir, "images")
                cachePath.mkdirs()
                val file = File(cachePath, "line_drawing_${System.currentTimeMillis()}.png")
                val fOut = FileOutputStream(file)
                lineDrawingBitmap.compress(Bitmap.CompressFormat.PNG, 100, fOut)
                fOut.close()
                val newUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)

                updateState(uiState.value.copy(
                    overlayImageUri = newUri,
                    isLineDrawing = true,
                    isLoading = false
                ))
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
            originalOverlayImageUri = uri,
            backgroundRemovedImageUri = null,
            isLineDrawing = false,
            showDoubleTapHint = showHint
        ))
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

    fun onCurvesPointsChanged(points: List<Offset>) {
        updateState(uiState.value.copy(curvesPoints = points), isUndoable = false)
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
        Toast.makeText(getApplication(), "Blend Mode: ${nextMode}", Toast.LENGTH_SHORT).show()
        updateState(uiState.value.copy(blendMode = nextMode))
    }

    fun saveProject(projectName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Save target images to cache and get URIs
                val savedTargetUris = uiState.value.capturedTargetImages.mapNotNull { bitmap ->
                    saveBitmapToCache(bitmap)
                }

                val projectData = ProjectData(
                    backgroundImageUri = uiState.value.backgroundImageUri,
                    overlayImageUri = uiState.value.overlayImageUri,
                    originalOverlayImageUri = uiState.value.originalOverlayImageUri,
                    targetImageUris = savedTargetUris,
                    refinementPaths = uiState.value.refinementPaths,
                    opacity = uiState.value.opacity,
                    brightness = uiState.value.brightness,
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
                    drawingPaths = uiState.value.drawingPaths,
                    progressPercentage = uiState.value.progressPercentage,
                    evolutionImageUris = uiState.value.evolutionCaptureUris,
                    gpsData = getGpsData(),
                    sensorData = getSensorData()
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

    private suspend fun getSensorData(): com.hereliesaz.graffitixr.data.SensorData? {
        return suspendCancellableCoroutine { cont ->
            val sensorManager = getApplication<Application>().getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager
            if (sensorManager == null) {
                if (cont.isActive) cont.resume(null)
                return@suspendCancellableCoroutine
            }

            val accelerometer = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
            val magnetometer = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_MAGNETIC_FIELD)

            if (accelerometer == null || magnetometer == null) {
                if (cont.isActive) cont.resume(null)
                return@suspendCancellableCoroutine
            }

            var gravity: FloatArray? = null
            var geomagnetic: FloatArray? = null

            val listener = object : android.hardware.SensorEventListener {
                override fun onSensorChanged(event: android.hardware.SensorEvent?) {
                    event ?: return
                    if (event.sensor.type == android.hardware.Sensor.TYPE_ACCELEROMETER) {
                        gravity = event.values.clone()
                    }
                    if (event.sensor.type == android.hardware.Sensor.TYPE_MAGNETIC_FIELD) {
                        geomagnetic = event.values.clone()
                    }
                    if (gravity != null && geomagnetic != null) {
                        val R = FloatArray(9)
                        val I = FloatArray(9)
                        if (android.hardware.SensorManager.getRotationMatrix(R, I, gravity, geomagnetic)) {
                            val orientation = FloatArray(3)
                            android.hardware.SensorManager.getOrientation(R, orientation)
                            val sensorData = com.hereliesaz.graffitixr.data.SensorData(
                                azimuth = orientation[0],
                                pitch = orientation[1],
                                roll = orientation[2]
                            )
                            sensorManager.unregisterListener(this)
                            if (cont.isActive) cont.resume(sensorData)
                        }
                    }
                }
                override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
            }

            sensorManager.registerListener(listener, accelerometer, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)
            sensorManager.registerListener(listener, magnetometer, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)

            // Timeout
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            handler.postDelayed({
                sensorManager.unregisterListener(listener)
                if (cont.isActive) cont.resume(null)
            }, 1000)

            cont.invokeOnCancellation {
                sensorManager.unregisterListener(listener)
                handler.removeCallbacksAndMessages(null)
            }
        }
    }

    private fun getGpsData(): com.hereliesaz.graffitixr.data.GpsData? {
        try {
            if (androidx.core.content.ContextCompat.checkSelfPermission(getApplication(), android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                androidx.core.content.ContextCompat.checkSelfPermission(getApplication(), android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return null
            }
            val locationManager = getApplication<Application>().getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
            val location = locationManager?.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                ?: locationManager?.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
            return location?.let {
                com.hereliesaz.graffitixr.data.GpsData(it.latitude, it.longitude, it.altitude, it.accuracy, it.time)
            }
        } catch (e: Exception) {
            return null
        }
    }

    private fun saveBitmapToCache(bitmap: Bitmap, prefix: String = "target"): Uri? {
        try {
            val context = getApplication<Application>().applicationContext
            val cachePath = File(context.cacheDir, "project_assets")
            cachePath.mkdirs()
            val file = File(cachePath, "${prefix}_${System.currentTimeMillis()}_${java.util.UUID.randomUUID()}.png")
            val fOut = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fOut)
            fOut.close()
            return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    fun loadProject(projectName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                projectManager.loadProject(projectName)?.let { projectData ->
                    updateState(uiState.value.copy(
                        backgroundImageUri = projectData.backgroundImageUri,
                        overlayImageUri = projectData.overlayImageUri,
                        originalOverlayImageUri = projectData.originalOverlayImageUri ?: projectData.overlayImageUri,
                        opacity = projectData.opacity,
                        brightness = projectData.brightness,
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
                        isLineDrawing = projectData.isLineDrawing
                    ))

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
                        val releases = json.decodeFromString<List<GithubRelease>>(response.toString())

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

    companion object {
        private const val MAX_UNDO_STACK_SIZE = 50
    }
}