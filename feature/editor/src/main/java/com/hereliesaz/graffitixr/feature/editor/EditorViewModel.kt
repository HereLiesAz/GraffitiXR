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
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.OverlayLayer
import com.hereliesaz.graffitixr.common.model.RotationAxis
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.feature.editor.export.ExportManager
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
    private val exportManager: ExportManager,
    private val dispatchers: DispatcherProvider
) : ViewModel(), EditorActions {

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

    private fun restoreProjectState(project: GraffitiProject) {
        val isFirstLoad = lastRestoredProjectId != project.id
        lastRestoredProjectId = project.id

        isRestoring = true
        viewModelScope.launch(dispatchers.io) {
            try {
                val restoredLayers = project.layers.mapNotNull { overlayLayer ->
                    loadScaledBitmap(overlayLayer.uri)?.let { bitmap ->
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

                val backgroundBitmap = project.backgroundImageUri?.let { loadScaledBitmap(it) }

                _uiState.update {
                    it.copy(
                        projectId = project.id,
                        layers = restoredLayers,
                        backgroundImageUri = project.backgroundImageUri?.toString(),
                        backgroundBitmap = backgroundBitmap,
                        mapPath = project.mapPath,
                        activeLayerId = if (isFirstLoad) restoredLayers.firstOrNull()?.id else it.activeLayerId,
                        isRightHanded = project.isRightHanded,
                        editorMode = if (isFirstLoad) EditorMode.AR else it.editorMode
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

    override fun onAddLayer(uri: Uri) {
        viewModelScope.launch(dispatchers.io) {
            val bitmap = loadScaledBitmap(uri)
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

    override fun onLayerDuplicated(id: String) {
        val layer = _uiState.value.layers.find { it.id == id } ?: return
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

    override fun copyLayerModifications(id: String) {
        val layer = _uiState.value.layers.find { it.id == id } ?: return
        copiedLayerAttributes = layer
        viewModelScope.launch { _message.emit("Edits copied") }
    }

    override fun pasteLayerModifications(id: String) {
        val source = copiedLayerAttributes
        if (source == null) {
            viewModelScope.launch { _message.emit("No edits copied") }
            return
        }

        saveState()
        _uiState.update { state ->
            val updatedLayers = state.layers.map { target ->
                if (target.id == id) {
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

    override fun onDrawingPathFinished(path: List<Offset>) {
        onDrawingPathFinished(path, _uiState.value.activeTool)
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

            val p = android.graphics.Path()
            if (points.isNotEmpty()) {
                p.moveTo(points.first().x, points.first().y)
                for (i in 1 until points.size) {
                    p.lineTo(points[i].x, points[i].y)
                }
            }
            canvas.drawPath(p, paint)

            saveDestructiveState()

            _uiState.update { state ->
                state.copy(
                    layers = state.layers.map {
                        if (it.id == activeLayer.id) it.copy(bitmap = workingBitmap) else it
                    }
                )
            }

            bitmap.recycle()
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

    override fun onUndoClicked() {
        if (undoStack.isNotEmpty()) {
            val previousState = undoStack.removeLast()
            redoStack.addLast(_uiState.value)

            viewModelScope.launch(dispatchers.io) {
                val restoredLayers = previousState.layers.map { layer ->
                    val bitmap = loadScaledBitmap(layer.uri) ?: layer.bitmap
                    layer.copy(bitmap = bitmap)
                }
                withContext(dispatchers.main) {
                    _uiState.value = previousState.copy(layers = restoredLayers, canUndo = undoStack.isNotEmpty(), canRedo = true, undoCount = undoStack.size, redoCount = redoStack.size)
                }
            }
        }
    }

    override fun onRedoClicked() {
        if (redoStack.isNotEmpty()) {
            val nextState = redoStack.removeLast()
            undoStack.addLast(_uiState.value)

            viewModelScope.launch(dispatchers.io) {
                val restoredLayers = nextState.layers.map { layer ->
                    val bitmap = loadScaledBitmap(layer.uri) ?: layer.bitmap
                    layer.copy(bitmap = bitmap)
                }
                withContext(dispatchers.main) {
                    _uiState.value = nextState.copy(layers = restoredLayers, canUndo = true, canRedo = redoStack.isNotEmpty(), undoCount = undoStack.size, redoCount = redoStack.size)
                }
            }
        }
    }

    override fun onMagicClicked() {
        saveState()
        updateActiveLayer {
            it.copy(contrast = 1.2f, saturation = 1.3f, brightness = 0.1f)
        }
    }

    override fun onAdjustmentStart() {
        if (!isAdjusting) {
            saveState()
            isAdjusting = true
        }
    }

    override fun onAdjustmentEnd() {
        isAdjusting = false
    }

    fun setBackgroundImage(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch(dispatchers.io) {
            val bitmap = loadScaledBitmap(uri)
            _uiState.update { it.copy(backgroundImageUri = uri.toString(), backgroundBitmap = bitmap) }
        }
    }

    override fun onOpacityChanged(v: Float) { updateActiveLayer { it.copy(opacity = v) } }
    override fun onBrightnessChanged(v: Float) { updateActiveLayer { it.copy(brightness = v) } }
    override fun onContrastChanged(v: Float) { updateActiveLayer { it.copy(contrast = v) } }
    override fun onSaturationChanged(v: Float) { updateActiveLayer { it.copy(saturation = v) } }
    override fun onColorBalanceRChanged(v: Float) { updateActiveLayer { it.copy(colorBalanceR = v) } }
    override fun onColorBalanceGChanged(v: Float) { updateActiveLayer { it.copy(colorBalanceG = v) } }
    override fun onColorBalanceBChanged(v: Float) { updateActiveLayer { it.copy(colorBalanceB = v) } }

    override fun onLayerActivated(id: String) {
        _uiState.update { state ->
            val layer = state.layers.find { it.id == id }
            state.copy(activeLayerId = id, isImageLocked = layer?.isImageLocked ?: false)
        }
    }

    override fun onLayerReordered(newOrder: List<String>) {
        _uiState.update { state ->
            val reordered = newOrder.mapNotNull { id -> state.layers.find { it.id == id } }
            state.copy(layers = reordered)
        }
    }

    override fun onLayerRenamed(id: String, name: String) {
        _uiState.update { state ->
            val newLayers = state.layers.map { if (it.id == id) it.copy(name = name) else it }
            state.copy(layers = newLayers)
        }
    }

    override fun onLayerRemoved(id: String) {
        _uiState.update { state ->
            val newLayers = state.layers.filter { it.id != id }
            val newActiveId = if (state.activeLayerId == id) null else state.activeLayerId
            val isLocked = if (newActiveId != null) {
                newLayers.find { it.id == newActiveId }?.isImageLocked ?: false
            } else false
            state.copy(layers = newLayers, activeLayerId = newActiveId, isImageLocked = isLocked)
        }
    }

    override fun toggleImageLock() {
        updateActiveLayer { it.copy(isImageLocked = !it.isImageLocked) }
    }

    fun toggleHandedness() {
        _uiState.update { it.copy(isRightHanded = !it.isRightHanded) }
    }

    override fun onScaleChanged(s: Float) {
        updateActiveLayer { it.copy(scale = it.scale * s) }
    }

    override fun onOffsetChanged(o: Offset) {
        updateActiveLayer { it.copy(offset = it.offset + o) }
    }

    override fun onRotationXChanged(d: Float) { updateActiveLayer { it.copy(rotationX = it.rotationX + d) } }
    override fun onRotationYChanged(d: Float) { updateActiveLayer { it.copy(rotationY = it.rotationY + d) } }
    override fun onRotationZChanged(d: Float) { updateActiveLayer { it.copy(rotationZ = it.rotationZ + d) } }

    override fun onCycleRotationAxis() {
        _uiState.update {
            val nextAxis = when (it.activeRotationAxis) {
                RotationAxis.X -> RotationAxis.Y
                RotationAxis.Y -> RotationAxis.Z
                RotationAxis.Z -> RotationAxis.X
            }
            it.copy(activeRotationAxis = nextAxis)
        }
    }

    override fun onGestureStart() {}
    override fun onGestureEnd() {}

    override fun setLayerTransform(scale: Float, offset: Offset, rx: Float, ry: Float, rz: Float) {
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

    override fun onLayerWarpChanged(layerId: String, mesh: List<Float>) {
        _uiState.update { state ->
            val newLayers = state.layers.map {
                if (it.id == layerId) it.copy(warpMesh = mesh) else it
            }
            state.copy(layers = newLayers)
        }
    }

    override fun onFeedbackShown() {}
    override fun onDoubleTapHintDismissed() { _uiState.update { it.copy(showDoubleTapHint = false) } }
    override fun onOnboardingComplete(mode: Any) {}
    override fun onAdjustClicked() { _uiState.update { it.copy(activePanel = EditorPanel.ADJUST) } }
    override fun onColorClicked() { _uiState.update { it.copy(activePanel = EditorPanel.COLOR) } }
    override fun onDismissPanel() { _uiState.update { it.copy(activePanel = EditorPanel.NONE) } }

    override fun onLineDrawingClicked() {
        val activeLayer = _uiState.value.layers.find { it.id == _uiState.value.activeLayerId } ?: return
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch(dispatchers.default) {
            val edgesBitmap = com.hereliesaz.graffitixr.common.util.ImageProcessor.detectEdges(activeLayer.bitmap) ?: return@launch

            val project = projectRepository.currentProject.value
            val savedUri = project?.let {
                val path = projectRepository.saveArtifact(it.id, "edges_${System.currentTimeMillis()}.png", com.hereliesaz.graffitixr.feature.editor.BitmapUtils.bitmapToByteArray(edgesBitmap))
                if (path != null) Uri.parse("file://$path") else null
            } ?: activeLayer.uri

            updateActiveLayer { it.copy(bitmap = edgesBitmap, uri = savedUri) }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    override fun onRemoveBackgroundClicked() {
        val activeLayer = _uiState.value.layers.find { it.id == _uiState.value.activeLayerId } ?: return
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch(dispatchers.default) {
            val result = backgroundRemover.removeBackground(activeLayer.bitmap)
            result.onSuccess { bgRemovedBitmap ->
                val project = projectRepository.currentProject.value
                val savedUri = project?.let {
                    val path = projectRepository.saveArtifact(it.id, "isolated_${System.currentTimeMillis()}.png", com.hereliesaz.graffitixr.feature.editor.BitmapUtils.bitmapToByteArray(bgRemovedBitmap))
                    if (path != null) Uri.parse("file://$path") else null
                } ?: activeLayer.uri

                updateActiveLayer { it.copy(bitmap = bgRemovedBitmap, uri = savedUri) }
            }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    override fun onCycleBlendMode() {
        updateActiveLayer { layer ->
            val modes = BlendMode.entries.toTypedArray()
            val nextIndex = (layer.blendMode.ordinal + 1) % modes.size
            layer.copy(blendMode = modes[nextIndex])
        }
    }

    fun exportProject() {
        _exportTrigger.value = true
        viewModelScope.launch(dispatchers.io) {
            try {
                val width = 1920
                val height = 1080
                val bitmap = exportManager.exportSingleImage(_uiState.value.layers, width, height)

                com.hereliesaz.graffitixr.common.util.saveBitmapToGallery(context, bitmap)
                bitmap.recycle()

                _message.emit("Export successful")
            } catch (e: Exception) {
                Log.e("EditorViewModel", "Export failed", e)
                _message.emit("Export failed")
            } finally {
                _exportTrigger.value = false
            }
        }
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

    private suspend fun loadScaledBitmap(uri: Uri): Bitmap? {
        return withContext(dispatchers.io) {
            try {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }

                if (uri.scheme == "file") {
                    BitmapFactory.decodeFile(uri.path, options)
                } else {
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, options)
                    }
                }

                // Downsample to fit within 2048x2048 to avoid OOM
                var sampleSize = 1
                while (options.outWidth / sampleSize > 2048 || options.outHeight / sampleSize > 2048) {
                    sampleSize *= 2
                }

                val loadOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inMutable = true // Allow modification
                }

                if (uri.scheme == "file") {
                    BitmapFactory.decodeFile(uri.path, loadOptions)
                } else {
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, loadOptions)
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
            val currentState = _uiState.value
            val currentProjectId = currentState.projectId
            val currentRepoProject = projectRepository.currentProject.value

            val overlayLayers = currentState.layers.map { layer ->
                OverlayLayer(
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

            if (currentProjectId != null) {
                val projectToUpdate = if (currentRepoProject?.id == currentProjectId) {
                    currentRepoProject
                } else {
                    projectRepository.getProject(currentProjectId)
                }

                if (projectToUpdate != null) {
                    val updatedProject = projectToUpdate.copy(
                        name = name ?: projectToUpdate.name,
                        layers = overlayLayers,
                        backgroundImageUri = if (currentState.backgroundImageUri != null) Uri.parse(currentState.backgroundImageUri) else null,
                        mapPath = currentState.mapPath,
                        isRightHanded = currentState.isRightHanded,
                        lastModified = System.currentTimeMillis()
                    )
                    projectRepository.updateProject(updatedProject)
                } else {
                    Log.e("EditorViewModel", "Project ID $currentProjectId missing in repo. Creating new.")
                    createNewProject(name, overlayLayers, currentState)
                }
            } else {
                createNewProject(name, overlayLayers, currentState)
            }
            isSaving = false
        }
    }

    private suspend fun createNewProject(name: String?, layers: List<OverlayLayer>, state: EditorUiState) {
        val newProject = GraffitiProject(
            id = UUID.randomUUID().toString(),
            name = name ?: "Untitled Project",
            layers = layers,
            backgroundImageUri = if (state.backgroundImageUri != null) Uri.parse(state.backgroundImageUri) else null,
            mapPath = state.mapPath,
            isRightHanded = state.isRightHanded,
            lastModified = System.currentTimeMillis()
        )
        projectRepository.createProject(newProject)
        _uiState.update { it.copy(projectId = newProject.id) }
    }

    // --- Native Toolkit Binding Implementations ---

    fun applyCurvesLut(points: List<Offset>) {
        val activeLayer = _uiState.value.layers.find { it.id == _uiState.value.activeLayerId } ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val lutArray = withContext(dispatchers.default) {
                // Call top-level function directly
                createLut(points)
            }

            val newBitmap = withContext(dispatchers.default) {
                val bmpCopy = activeLayer.bitmap.copy(Bitmap.Config.ARGB_8888, true)
                // slamManager.applyLut(bmpCopy, lutArray) // Uncomment once native signature is added
                bmpCopy
            }

            saveDestructiveState()

            _uiState.update { state ->
                val updatedLayers = state.layers.map { layer ->
                    if (layer.id == state.activeLayerId) layer.copy(bitmap = newBitmap) else layer
                }
                state.copy(layers = updatedLayers, isLoading = false)
            }
        }
    }

    fun applyLiquifyTool(meshData: FloatArray) {
        val activeLayer = _uiState.value.layers.find { it.id == _uiState.value.activeLayerId } ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val newBitmap = withContext(dispatchers.default) {
                val bmpCopy = activeLayer.bitmap.copy(Bitmap.Config.ARGB_8888, true)
                // Calls directly down to the JNI wrapper in SlamManager (Comment out if signature isn't implemented in native yet)
                // slamManager.processLiquify(bmpCopy, meshData)
                bmpCopy
            }

            saveDestructiveState()

            _uiState.update { state ->
                val updatedLayers = state.layers.map { layer ->
                    if (layer.id == state.activeLayerId) layer.copy(bitmap = newBitmap) else layer
                }
                state.copy(layers = updatedLayers, isLoading = false)
            }
        }
    }
}