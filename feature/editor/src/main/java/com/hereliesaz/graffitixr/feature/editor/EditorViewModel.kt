package com.hereliesaz.graffitixr.feature.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.common.DispatcherProvider
import com.hereliesaz.graffitixr.common.model.BlendMode
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.EditorPanel
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.RotationAxis
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject

@OptIn(FlowPreview::class)
@HiltViewModel
class EditorViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    @ApplicationContext private val context: Context,
    private val backgroundRemover: BackgroundRemover,
    private val slamManager: SlamManager,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val _exportTrigger = MutableStateFlow(false)
    val exportTrigger: StateFlow<Boolean> = _exportTrigger.asStateFlow()

    private val _message = MutableSharedFlow<String>()
    val message: SharedFlow<String> = _message.asSharedFlow()

    private var copiedLayerAttributes: Layer? = null

    private val undoStack = ArrayDeque<EditorUiState>()
    private val redoStack = ArrayDeque<EditorUiState>()
    private val maxStackSize = 20
    private var isAdjusting = false

    private var isRestoring = false
    private var isSaving = false
    private var lastRestoredProjectId: String? = null

    init {
        viewModelScope.launch(dispatchers.main) {
            projectRepository.currentProject.collectLatest { project ->
                if (project != null && !isRestoring && !isSaving) {
                    restoreProjectState(project)
                }
            }
        }

        viewModelScope.launch(dispatchers.io) {
            _uiState
                .debounce(2000L)
                .distinctUntilChanged { old, new ->
                    old.layers == new.layers &&
                            old.backgroundImageUri == new.backgroundImageUri &&
                            old.mapPath == new.mapPath &&
                            old.isRightHanded == new.isRightHanded
                }
                .collect {
                    if (!isRestoring && !isSaving) {
                        saveProject()
                    }
                }
        }
    }

    private fun restoreProjectState(project: com.hereliesaz.graffitixr.common.model.GraffitiProject) {
        val isFirstLoad = lastRestoredProjectId != project.id
        lastRestoredProjectId = project.id

        isRestoring = true
        viewModelScope.launch(dispatchers.io) {
            try {
                val restoredLayers = project.layers.mapNotNull { overlayLayer ->
                    loadBitmapFromUri(overlayLayer.uri)?.let { bitmap ->
                        Layer(
                            id = overlayLayer.id,
                            name = overlayLayer.name,
                            bitmap = bitmap,
                            uri = overlayLayer.uri,
                            offset = overlayLayer.offset,
                            scale = overlayLayer.scale,
                            rotationX = overlayLayer.rotationX,
                            rotationY = overlayLayer.rotationY,
                            rotationZ = overlayLayer.rotationZ,
                            opacity = overlayLayer.opacity,
                            blendMode = overlayLayer.blendMode,
                            brightness = overlayLayer.brightness,
                            contrast = overlayLayer.contrast,
                            saturation = overlayLayer.saturation,
                            colorBalanceR = overlayLayer.colorBalanceR,
                            colorBalanceG = overlayLayer.colorBalanceG,
                            colorBalanceB = overlayLayer.colorBalanceB,
                            isImageLocked = overlayLayer.isImageLocked,
                            isVisible = overlayLayer.isVisible,
                            warpMesh = overlayLayer.warpMesh,
                            isSketch = overlayLayer.isSketch
                        )
                    }
                }

                val backgroundBitmap = project.backgroundImageUri?.let { loadBitmapFromUri(it) }

                _uiState.update {
                    it.copy(
                        layers = restoredLayers,
                        backgroundImageUri = project.backgroundImageUri?.toString(),
                        backgroundBitmap = backgroundBitmap,
                        mapPath = project.mapPath,
                        activeLayerId = if (isFirstLoad) restoredLayers.firstOrNull()?.id else it.activeLayerId,
                        isRightHanded = project.isRightHanded,
                        editorMode = if (isFirstLoad) EditorMode.TRACE else it.editorMode
                    )
                }
            } catch (e: Exception) {
                Log.e("EditorViewModel", "Error restoring project state", e)
            } finally {
                isRestoring = false
            }
        }
    }

    fun onAddBlankLayer() {
        viewModelScope.launch(dispatchers.default) {
            val sketchCount = _uiState.value.layers.count { it.isSketch } + 1
            val bitmap = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
            val uri = Uri.parse("content://dummy_sketch_${UUID.randomUUID()}")
            val newLayer = Layer(
                id = UUID.randomUUID().toString(),
                name = "Sketch $sketchCount",
                bitmap = bitmap,
                uri = uri,
                isSketch = true
            )
            saveState()
            _uiState.update {
                it.copy(
                    layers = it.layers + newLayer,
                    activeLayerId = newLayer.id,
                    activeTool = Tool.BRUSH
                )
            }
        }
    }

    fun onAddLayer(uri: Uri) {
        viewModelScope.launch(dispatchers.io) {
            val bitmap = loadBitmapFromUri(uri)
            if (bitmap != null) {
                val fileName = getFileName(uri)
                val newLayer = Layer(
                    id = UUID.randomUUID().toString(),
                    name = fileName,
                    bitmap = bitmap,
                    uri = uri,
                    isSketch = false
                )
                saveState()
                _uiState.update {
                    it.copy(
                        layers = it.layers + newLayer,
                        activeLayerId = newLayer.id,
                        isImageLocked = newLayer.isImageLocked
                    )
                }
            }
        }
    }

    fun onDuplicateLayer(layerId: String) {
        val layer = _uiState.value.layers.find { it.id == layerId } ?: return
        saveState()

        viewModelScope.launch(dispatchers.io) {
            val newBitmap = layer.bitmap.copy(layer.bitmap.config ?: Bitmap.Config.ARGB_8888, true)
            val newId = UUID.randomUUID().toString()

            val currentFile = File(layer.uri.path ?: "")
            val newUri = if (currentFile.exists()) {
                val newFile = File(context.cacheDir, "layer_${newId}.png")
                currentFile.copyTo(newFile, overwrite = true)
                Uri.fromFile(newFile)
            } else {
                val newFile = File(context.cacheDir, "layer_${newId}.png")
                FileOutputStream(newFile).use { out ->
                    newBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                Uri.fromFile(newFile)
            }

            val newLayer = layer.copy(
                id = newId,
                name = "${layer.name} Copy",
                bitmap = newBitmap,
                uri = newUri
            )

            withContext(dispatchers.main) {
                _uiState.update { it.copy(layers = it.layers + newLayer, activeLayerId = newLayer.id) }
            }
        }
    }

    fun onCopyLayerEdits(layerId: String) {
        val layer = _uiState.value.layers.find { it.id == layerId } ?: return
        copiedLayerAttributes = layer
        viewModelScope.launch { _message.emit("Edits copied") }
    }

    fun onPasteLayerEdits(layerId: String) {
        val source = copiedLayerAttributes
        if (source == null) {
            viewModelScope.launch { _message.emit("No edits copied") }
            return
        }

        saveState()
        _uiState.update { state ->
            val updatedLayers = state.layers.map { target ->
                if (target.id == layerId) {
                    target.copy(
                        opacity = source.opacity,
                        blendMode = source.blendMode,
                        brightness = source.brightness,
                        contrast = source.contrast,
                        saturation = source.saturation,
                        colorBalanceR = source.colorBalanceR,
                        colorBalanceG = source.colorBalanceG,
                        colorBalanceB = source.colorBalanceB,
                        scale = source.scale,
                        rotationX = source.rotationX,
                        rotationY = source.rotationY,
                        rotationZ = source.rotationZ
                    )
                } else target
            }
            state.copy(layers = updatedLayers)
        }
    }

    fun onFlipLayer(horizontal: Boolean) {
        val activeLayer = _uiState.value.layers.find { it.id == _uiState.value.activeLayerId } ?: return
        saveDestructiveState()

        val matrix = Matrix().apply {
            if (horizontal) preScale(-1f, 1f) else preScale(1f, -1f)
        }

        try {
            val flippedBitmap = Bitmap.createBitmap(
                activeLayer.bitmap, 0, 0, activeLayer.bitmap.width, activeLayer.bitmap.height, matrix, true
            )
            updateActiveLayer { it.copy(bitmap = flippedBitmap) }
        } catch (e: Exception) {
            Log.e("EditorViewModel", "Error flipping layer", e)
        }
    }

    private fun getFileName(uri: Uri): String {
        var name = "Layer"
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (index != -1) name = cursor.getString(index)
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        } else if (uri.scheme == "file") {
            name = File(uri.path ?: "").name
        }
        return name.substringBeforeLast(".")
    }

    fun onDrawingPathFinished(points: List<Offset>, tool: Tool) {
        val activeLayer = _uiState.value.layers.find { it.id == _uiState.value.activeLayerId } ?: return
        if (!activeLayer.isSketch) return

        viewModelScope.launch(dispatchers.default) {
            val bitmap = activeLayer.bitmap
            val workingBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(workingBitmap)

            val paint = Paint().apply {
                color = _uiState.value.activeColor.toArgb()
                strokeWidth = _uiState.value.brushSize
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                isAntiAlias = true
                if (tool == Tool.ERASER) {
                    xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
                }
            }

            val path = android.graphics.Path()
            if (points.isNotEmpty()) {
                path.moveTo(points.first().x, points.first().y)
                for (i in 1 until points.size) {
                    path.lineTo(points[i].x, points[i].y)
                }
            }
            canvas.drawPath(path, paint)

            saveDestructiveState()

            _uiState.update { state ->
                state.copy(
                    layers = state.layers.map {
                        if (it.id == activeLayer.id) it.copy(bitmap = workingBitmap) else it
                    }
                )
            }
        }
    }

    fun setActiveTool(tool: Tool) { _uiState.update { it.copy(activeTool = tool) } }
    fun setShowColorPicker(show: Boolean) { _uiState.update { it.copy(showColorPicker = show) } }
    fun setShowSizePicker(show: Boolean) { _uiState.update { it.copy(showSizePicker = show) } }
    fun setSize(size: Float) { _uiState.update { it.copy(brushSize = size) } }

    fun setColor(color: Color) {
        _uiState.update { state ->
            val newHistory = state.colorHistory.toMutableList()
            newHistory.remove(color)
            newHistory.add(0, color)
            state.copy(activeColor = color, colorHistory = newHistory.take(12))
        }
    }

    fun setEditorMode(mode: EditorMode) {
        _uiState.update { it.copy(editorMode = mode) }
    }

    private fun saveState() {
        if (undoStack.size >= maxStackSize) {
            val removed = undoStack.removeFirst()
            removed.layers.forEach { File(it.uri.path ?: "").delete() }
        }
        undoStack.addLast(_uiState.value)
        redoStack.clear()
        _uiState.update { it.copy(canUndo = true, canRedo = false, undoCount = undoStack.size, redoCount = 0) }
    }

    fun saveDestructiveState() {
        val currentState = _uiState.value
        val stateId = UUID.randomUUID().toString()

        val layerBitmapsToSave = currentState.layers.associate { layer ->
            layer.id to (if (layer.id == currentState.activeLayerId) layer.bitmap.copy(layer.bitmap.config ?: Bitmap.Config.ARGB_8888, true) else layer.bitmap)
        }

        viewModelScope.launch(dispatchers.io) {
            val historyLayers = currentState.layers.map { layer ->
                val bmp = layerBitmapsToSave[layer.id] ?: layer.bitmap
                val file = File(context.cacheDir, "history_${stateId}_${layer.id}.png")
                FileOutputStream(file).use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                if (layer.id == currentState.activeLayerId) {
                    bmp.recycle()
                }
                layer.copy(bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8), uri = Uri.fromFile(file))
            }
            val historyState = currentState.copy(layers = historyLayers)

            withContext(dispatchers.main) {
                if (undoStack.size >= maxStackSize) {
                    val removed = undoStack.removeFirst()
                    removed.layers.forEach { File(it.uri.path ?: "").delete() }
                }
                undoStack.addLast(historyState)
                redoStack.forEach { state -> state.layers.forEach { File(it.uri.path ?: "").delete() } }
                redoStack.clear()
                _uiState.update { it.copy(canUndo = true, canRedo = false, undoCount = undoStack.size, redoCount = 0) }
            }
        }
    }

    fun onUndoClicked() {
        if (undoStack.isNotEmpty()) {
            val previousState = undoStack.removeLast()
            redoStack.addLast(_uiState.value)

            viewModelScope.launch(dispatchers.io) {
                val restoredLayers = previousState.layers.map { layer ->
                    val bitmap = loadBitmapFromUri(layer.uri) ?: layer.bitmap
                    layer.copy(bitmap = bitmap)
                }
                withContext(dispatchers.main) {
                    _uiState.value = previousState.copy(layers = restoredLayers, canUndo = undoStack.isNotEmpty(), canRedo = true, undoCount = undoStack.size, redoCount = redoStack.size)
                }
            }
        }
    }

    fun onRedoClicked() {
        if (redoStack.isNotEmpty()) {
            val nextState = redoStack.removeLast()
            undoStack.addLast(_uiState.value)

            viewModelScope.launch(dispatchers.io) {
                val restoredLayers = nextState.layers.map { layer ->
                    val bitmap = loadBitmapFromUri(layer.uri) ?: layer.bitmap
                    layer.copy(bitmap = bitmap)
                }
                withContext(dispatchers.main) {
                    _uiState.value = nextState.copy(layers = restoredLayers, canUndo = true, canRedo = redoStack.isNotEmpty(), undoCount = undoStack.size, redoCount = redoStack.size)
                }
            }
        }
    }

    fun onMagicClicked() {
        saveState()
        updateActiveLayer {
            it.copy(contrast = 1.2f, saturation = 1.3f, brightness = 0.1f)
        }
    }

    fun onAdjustmentStart() {
        if (!isAdjusting) {
            saveState()
            isAdjusting = true
        }
    }

    fun onAdjustmentEnd() {
        isAdjusting = false
    }

    fun setBackgroundImage(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch(dispatchers.io) {
            val bitmap = loadBitmapFromUri(uri)
            _uiState.update { it.copy(backgroundImageUri = uri.toString(), backgroundBitmap = bitmap) }
        }
    }

    fun onOpacityChanged(v: Float) { updateActiveLayer { it.copy(opacity = v) } }
    fun onBrightnessChanged(v: Float) { updateActiveLayer { it.copy(brightness = v) } }
    fun onContrastChanged(v: Float) { updateActiveLayer { it.copy(contrast = v) } }
    fun onSaturationChanged(v: Float) { updateActiveLayer { it.copy(saturation = v) } }
    fun onColorBalanceRChanged(v: Float) { updateActiveLayer { it.copy(colorBalanceR = v) } }
    fun onColorBalanceGChanged(v: Float) { updateActiveLayer { it.copy(colorBalanceG = v) } }
    fun onColorBalanceBChanged(v: Float) { updateActiveLayer { it.copy(colorBalanceB = v) } }

    fun onLayerActivated(layerId: String) {
        _uiState.update { state ->
            val layer = state.layers.find { it.id == layerId }
            state.copy(activeLayerId = layerId, isImageLocked = layer?.isImageLocked ?: false)
        }
    }

    fun onLayerReordered(newOrder: List<String>) {
        _uiState.update { state ->
            val reordered = newOrder.mapNotNull { id -> state.layers.find { it.id == id } }
            state.copy(layers = reordered)
        }
    }

    fun onLayerRenamed(layerId: String, newName: String) {
        _uiState.update { state ->
            val newLayers = state.layers.map { if (it.id == layerId) it.copy(name = newName) else it }
            state.copy(layers = newLayers)
        }
    }

    fun onLayerRemoved(layerId: String) {
        _uiState.update { state ->
            val newLayers = state.layers.filter { it.id != layerId }
            val newActiveId = if (state.activeLayerId == layerId) null else state.activeLayerId
            val isLocked = if (newActiveId != null) {
                newLayers.find { it.id == newActiveId }?.isImageLocked ?: false
            } else false
            state.copy(layers = newLayers, activeLayerId = newActiveId, isImageLocked = isLocked)
        }
    }

    fun toggleImageLock() {
        updateActiveLayer { it.copy(isImageLocked = !it.isImageLocked) }
    }

    fun toggleHandedness() {
        _uiState.update { it.copy(isRightHanded = !it.isRightHanded) }
    }

    fun onScaleChanged(scale: Float) {
        updateActiveLayer { it.copy(scale = it.scale * scale) }
    }

    fun onOffsetChanged(offset: Offset) {
        updateActiveLayer { it.copy(offset = it.offset + offset) }
    }

    fun onRotationXChanged(d: Float) { updateActiveLayer { it.copy(rotationX = it.rotationX + d) } }
    fun onRotationYChanged(d: Float) { updateActiveLayer { it.copy(rotationY = it.rotationY + d) } }
    fun onRotationZChanged(d: Float) { updateActiveLayer { it.copy(rotationZ = it.rotationZ + d) } }

    fun onCycleRotationAxis() {
        _uiState.update {
            val nextAxis = when (it.activeRotationAxis) {
                RotationAxis.X -> RotationAxis.Y
                RotationAxis.Y -> RotationAxis.Z
                RotationAxis.Z -> RotationAxis.X
            }
            it.copy(activeRotationAxis = nextAxis)
        }
    }

    fun onGestureStart() {}
    fun onGestureEnd() {}

    fun setLayerTransform(scale: Float, offset: Offset, rx: Float, ry: Float, rz: Float) {
        updateActiveLayer { it.copy(scale = scale, offset = offset, rotationX = rx, rotationY = ry, rotationZ = rz) }
    }

    fun onTransformGesture(pan: Offset, zoom: Float, rotation: Float) {
        updateActiveLayer {
            it.copy(
                scale = it.scale * zoom,
                rotationZ = it.rotationZ + rotation,
                offset = it.offset + pan
            )
        }
    }

    fun onLayerWarpChanged(layerId: String, mesh: List<Float>) {
        _uiState.update { state ->
            val newLayers = state.layers.map {
                if (it.id == layerId) it.copy(warpMesh = mesh) else it
            }
            state.copy(layers = newLayers)
        }
    }

    fun onFeedbackShown() {}
    fun onDoubleTapHintDismissed() { _uiState.update { it.copy(showDoubleTapHint = false) } }
    fun onOnboardingComplete(mode: Any) {}
    fun onAdjustClicked() { _uiState.update { it.copy(activePanel = EditorPanel.ADJUST) } }
    fun onColorClicked() { _uiState.update { it.copy(activePanel = EditorPanel.COLOR) } }
    fun onDismissPanel() { _uiState.update { it.copy(activePanel = EditorPanel.NONE) } }

    fun onLineDrawingClicked() {
        val activeLayer = _uiState.value.layers.find { it.id == _uiState.value.activeLayerId } ?: return
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch(dispatchers.default) {
            val edgesBitmap = com.hereliesaz.graffitixr.common.util.ImageProcessor.detectEdges(activeLayer.bitmap) ?: return@launch

            val project = projectRepository.currentProject.value
            val savedUri = project?.let {
                val path = projectRepository.saveArtifact(it.id, "edges_${System.currentTimeMillis()}.png", BitmapUtils.bitmapToByteArray(edgesBitmap))
                if (path != null) Uri.parse("file://$path") else null
            } ?: activeLayer.uri

            updateActiveLayer { it.copy(bitmap = edgesBitmap, uri = savedUri) }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun onRemoveBackgroundClicked() {
        val activeLayer = _uiState.value.layers.find { it.id == _uiState.value.activeLayerId } ?: return
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch(dispatchers.default) {
            val result = backgroundRemover.removeBackground(activeLayer.bitmap)
            result.onSuccess { bgRemovedBitmap ->
                val project = projectRepository.currentProject.value
                val savedUri = project?.let {
                    val path = projectRepository.saveArtifact(it.id, "isolated_${System.currentTimeMillis()}.png", BitmapUtils.bitmapToByteArray(bgRemovedBitmap))
                    if (path != null) Uri.parse("file://$path") else null
                } ?: activeLayer.uri

                updateActiveLayer { it.copy(bitmap = bgRemovedBitmap, uri = savedUri) }
            }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun onCycleBlendMode() {
        updateActiveLayer { layer ->
            val modes = BlendMode.entries.toTypedArray()
            val nextIndex = (layer.blendMode.ordinal + 1) % modes.size
            layer.copy(blendMode = modes[nextIndex])
        }
    }

    fun exportProject() {
        _exportTrigger.value = true
    }

    fun onExportComplete() {
        _exportTrigger.value = false
    }

    private fun updateActiveLayer(update: (Layer) -> Layer) {
        _uiState.update { state ->
            val newLayers = state.layers.map { if (it.id == state.activeLayerId) update(it) else it }
            state.copy(layers = newLayers)
        }
    }

    private suspend fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return withContext(dispatchers.io) {
            try {
                if (uri.scheme == "file") {
                    BitmapFactory.decodeFile(uri.path)
                } else {
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        BitmapFactory.decodeStream(inputStream)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    fun saveProject(name: String? = null) {
        isSaving = true
        viewModelScope.launch(dispatchers.io) {
            val currentProject = projectRepository.currentProject.value
            val currentState = _uiState.value
            val overlayLayers = currentState.layers.map { layer ->
                com.hereliesaz.graffitixr.common.model.OverlayLayer(
                    id = layer.id,
                    name = layer.name,
                    uri = layer.uri,
                    offset = layer.offset,
                    scale = layer.scale,
                    rotationX = layer.rotationX,
                    rotationY = layer.rotationY,
                    rotationZ = layer.rotationZ,
                    opacity = layer.opacity,
                    blendMode = layer.blendMode,
                    brightness = layer.brightness,
                    contrast = layer.contrast,
                    saturation = layer.saturation,
                    colorBalanceR = layer.colorBalanceR,
                    colorBalanceG = layer.colorBalanceG,
                    colorBalanceB = layer.colorBalanceB,
                    isImageLocked = layer.isImageLocked,
                    isVisible = layer.isVisible,
                    warpMesh = layer.warpMesh,
                    isSketch = layer.isSketch
                )
            }

            if (currentProject != null) {
                val updatedProject = currentProject.copy(
                    name = name ?: currentProject.name,
                    layers = overlayLayers,
                    backgroundImageUri = if (currentState.backgroundImageUri != null) Uri.parse(currentState.backgroundImageUri) else null,
                    mapPath = currentState.mapPath,
                    isRightHanded = currentState.isRightHanded,
                    lastModified = System.currentTimeMillis()
                )
                projectRepository.updateProject(updatedProject)
            } else {
                val newProject = com.hereliesaz.graffitixr.common.model.GraffitiProject(
                    id = UUID.randomUUID().toString(),
                    name = name ?: "Untitled Project",
                    layers = overlayLayers,
                    backgroundImageUri = if (currentState.backgroundImageUri != null) Uri.parse(currentState.backgroundImageUri) else null,
                    mapPath = currentState.mapPath,
                    isRightHanded = currentState.isRightHanded,
                    lastModified = System.currentTimeMillis()
                )
                projectRepository.createProject(newProject)
            }
            isSaving = false
        }
    }
}