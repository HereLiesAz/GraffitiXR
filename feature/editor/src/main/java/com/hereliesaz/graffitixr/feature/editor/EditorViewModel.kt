// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorViewModel.kt
package com.hereliesaz.graffitixr.feature.editor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.common.DispatcherProvider
import com.hereliesaz.graffitixr.common.model.*
import com.hereliesaz.graffitixr.common.util.ImageUtils
import com.hereliesaz.graffitixr.common.util.saveBitmapToGallery
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import com.hereliesaz.graffitixr.data.ProjectManager
import com.hereliesaz.graffitixr.feature.editor.export.ExportManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import androidx.core.net.toUri
import androidx.core.graphics.createBitmap

data class StrokeCommand(
    val path: List<Offset>,
    val canvasSize: IntSize,
    val tool: Tool,
    val brushSize: Float,
    val brushColor: Int,
    val intensity: Float
)

sealed class EditCommand {
    data class PropertyChange(val oldLayers: List<Layer>) : EditCommand()
    data class Draw(val layerId: String, val command: StrokeCommand) : EditCommand()
}

@HiltViewModel
class EditorViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val projectManager: ProjectManager,
    private val exportManager: ExportManager,
    @ApplicationContext private val context: Context,
    private val backgroundRemover: BackgroundRemover,
    internal val slamManager: SlamManager,
    private val dispatchers: DispatcherProvider
) : ViewModel(), EditorActions {

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState = _uiState.asStateFlow()

    private val undoStack = ArrayDeque<EditCommand>()
    private val redoStack = ArrayDeque<EditCommand>()
    private val maxStackSize = 20

    private val baseBitmaps = mutableMapOf<String, Bitmap>()
    private val layerStrokes = mutableMapOf<String, MutableList<StrokeCommand>>()
    private var pendingSaveJob: kotlinx.coroutines.Job? = null

    private var copiedLayerState: Layer? = null

    init {
        viewModelScope.launch(dispatchers.main) {
            projectRepository.currentProject.collect { project ->
                if (project != null) {
                    val projectIdChanged = _uiState.value.projectId != project.id

                    if (projectIdChanged) {
                        val currentLayers = _uiState.value.layers
                        val layers = project.layers.map { overlayLayer ->
                            val existingLayer = currentLayers.find { it.id == overlayLayer.id }
                            val layer = overlayLayer.toLayer()
                            if (existingLayer != null && existingLayer.uri == layer.uri) {
                                layer.copy(bitmap = existingLayer.bitmap)
                            } else {
                                layer
                            }
                        }

                        _uiState.update { it.copy(projectId = project.id, layers = layers, activeTool = Tool.NONE) }

                        val layersToLoad = layers.filter { it.bitmap == null && it.uri != null }
                        if (layersToLoad.isNotEmpty()) {
                            viewModelScope.launch(dispatchers.io) {
                                val loadedLayers = layers.map { layer ->
                                    val layerUri = layer.uri
                                    if (layer.bitmap == null && layerUri != null) {
                                        val loadedBmp = ImageUtils.loadBitmapAsync(context, layerUri)
                                        if (loadedBmp != null) {
                                            baseBitmaps[layer.id] = loadedBmp.copy(Bitmap.Config.ARGB_8888, false)
                                            layerStrokes[layer.id] = mutableListOf()
                                        }
                                        layer.copy(bitmap = loadedBmp)
                                    } else {
                                        layer
                                    }
                                }
                                withContext(dispatchers.main) {
                                    _uiState.update { it.copy(layers = loadedLayers) }
                                }
                            }
                        }

                        viewModelScope.launch(dispatchers.io) {
                            slamManager.clearMap()
                            val mapPath = projectManager.getMapPath(context, project.id)
                            if (File(mapPath).exists()) {
                                slamManager.loadModel(mapPath)
                            }

                            project.fingerprint?.let { fp ->
                                slamManager.setTargetFingerprint(
                                    fp.descriptorsData,
                                    fp.descriptorsRows,
                                    fp.descriptorsCols,
                                    fp.descriptorsType,
                                    fp.points3d.toFloatArray()
                                )
                            }
                        }

                        project.backgroundImageUri?.let { uri ->
                            viewModelScope.launch(dispatchers.io) {
                                val bitmap = ImageUtils.loadBitmapAsync(context, uri)
                                withContext(dispatchers.main) {
                                    _uiState.update { it.copy(backgroundBitmap = bitmap) }
                                }
                            }
                        }
                    }
                } else {
                    _uiState.update { it.copy(projectId = null, layers = emptyList(), backgroundBitmap = null, activeTool = Tool.NONE) }
                    slamManager.clearMap()
                    baseBitmaps.clear()
                    layerStrokes.clear()
                    undoStack.clear()
                    redoStack.clear()
                }
            }
        }
    }

    fun setEditorMode(mode: EditorMode) = _uiState.update { it.copy(editorMode = mode) }

    private fun pushHistory() {
        val layersWithoutBitmaps = _uiState.value.layers.map { it.copy(bitmap = null) }
        if (undoStack.isNotEmpty()) {
            val last = undoStack.last()
            if (last is EditCommand.PropertyChange && last.oldLayers == layersWithoutBitmaps) return
        }
        undoStack.addLast(EditCommand.PropertyChange(layersWithoutBitmaps))
        if (undoStack.size > maxStackSize) undoStack.removeFirst()
        redoStack.clear()
        updateHistoryCounts()
    }

    private fun updateHistoryCounts() {
        _uiState.update { it.copy(undoCount = undoStack.size, redoCount = redoStack.size) }
    }

    override fun onUndoClicked() {
        if (undoStack.isEmpty()) return
        val command = undoStack.removeLast()

        when(command) {
            is EditCommand.Draw -> {
                redoStack.addLast(command)
                val strokes = layerStrokes[command.layerId] ?: return
                strokes.removeLast()
                rebuildLayerBitmap(command.layerId)
            }
            is EditCommand.PropertyChange -> {
                val currentProps = _uiState.value.layers.map { it.copy(bitmap = null) }
                redoStack.addLast(EditCommand.PropertyChange(currentProps))

                val currentBitmaps = _uiState.value.layers.associate { it.id to it.bitmap }
                val restoredLayers = command.oldLayers.map { it.copy(bitmap = currentBitmaps[it.id]) }
                _uiState.update { it.copy(layers = restoredLayers) }
                saveProject()
            }
        }
        updateHistoryCounts()
    }

    override fun onRedoClicked() {
        if (redoStack.isEmpty()) return
        val command = redoStack.removeLast()

        when(command) {
            is EditCommand.Draw -> {
                undoStack.addLast(command)
                val strokes = layerStrokes[command.layerId] ?: mutableListOf()
                strokes.add(command.command)
                layerStrokes[command.layerId] = strokes
                rebuildLayerBitmap(command.layerId)
            }
            is EditCommand.PropertyChange -> {
                val currentProps = _uiState.value.layers.map { it.copy(bitmap = null) }
                undoStack.addLast(EditCommand.PropertyChange(currentProps))

                val currentBitmaps = _uiState.value.layers.associate { it.id to it.bitmap }
                val restoredLayers = command.oldLayers.map { it.copy(bitmap = currentBitmaps[it.id]) }
                _uiState.update { it.copy(layers = restoredLayers) }
                saveProject()
            }
        }
        updateHistoryCounts()
    }

    private fun rebuildLayerBitmap(layerId: String) {
        val base = baseBitmaps[layerId] ?: return
        val strokes = layerStrokes[layerId] ?: emptyList()

        viewModelScope.launch(dispatchers.default) {
            var currentBitmap = base.copy(Bitmap.Config.ARGB_8888, true)

            for (stroke in strokes) {
                val mapped = com.hereliesaz.graffitixr.feature.editor.util.ImageProcessor.mapScreenToBitmap(
                    stroke.path, stroke.canvasSize.width, stroke.canvasSize.height, currentBitmap.width, currentBitmap.height
                )
                currentBitmap = com.hereliesaz.graffitixr.feature.editor.util.ImageProcessor.applyToolToBitmap(
                    currentBitmap, mapped, stroke.tool, stroke.brushSize, stroke.brushColor, stroke.intensity, true
                )
            }

            withContext(dispatchers.main) {
                _uiState.update { state ->
                    state.copy(layers = state.layers.map { if (it.id == layerId) it.copy(bitmap = currentBitmap) else it })
                }
                val layer = _uiState.value.layers.find { it.id == layerId } ?: return@withContext
                scheduleDiskSave(layerId, currentBitmap, layer.uri)
            }
        }
    }

    fun processNewStroke(layerId: String, activeBitmap: Bitmap, command: StrokeCommand, layer: Layer) {
        val currentStrokes = layerStrokes[layerId] ?: mutableListOf()
        currentStrokes.add(command)
        layerStrokes[layerId] = currentStrokes

        undoStack.addLast(EditCommand.Draw(layerId, command))
        if (undoStack.size > maxStackSize) undoStack.removeFirst()
        redoStack.clear()
        updateHistoryCounts()

        viewModelScope.launch(dispatchers.default) {
            val mappedStroke = com.hereliesaz.graffitixr.feature.editor.util.ImageProcessor.mapScreenToBitmap(
                command.path,
                command.canvasSize.width, command.canvasSize.height,
                activeBitmap.width, activeBitmap.height
            )

            val newBitmap = com.hereliesaz.graffitixr.feature.editor.util.ImageProcessor.applyToolToBitmap(
                activeBitmap, mappedStroke, command.tool, command.brushSize, command.brushColor, command.intensity, false
            )

            withContext(dispatchers.main) {
                _uiState.update { state ->
                    state.copy(layers = state.layers.map { if (it.id == layerId) it.copy(bitmap = newBitmap) else it })
                }
            }

            scheduleDiskSave(layerId, newBitmap, layer.uri)
        }
    }

    private fun scheduleDiskSave(layerId: String, bitmap: Bitmap, uri: Uri?) {
        val path = uri?.path ?: return
        pendingSaveJob?.cancel()
        pendingSaveJob = viewModelScope.launch(dispatchers.io) {
            kotlinx.coroutines.delay(1500)
            try {
                val file = java.io.File(path)
                java.io.FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onAddLayer(uri: Uri) {
        pushHistory()
        viewModelScope.launch(dispatchers.io) {
            val bitmap = ImageUtils.loadBitmapAsync(context, uri)
            val projectId = _uiState.value.projectId
            if (bitmap != null && projectId != null) {
                val filename = "layer_${UUID.randomUUID()}.png"
                val path = projectRepository.saveArtifact(projectId, filename, ImageUtils.bitmapToByteArray(bitmap))
                val localUri = "file://$path".toUri()

                val newLayer = Layer(
                    id = UUID.randomUUID().toString(),
                    name = "Layer ${_uiState.value.layers.size + 1}",
                    uri = localUri,
                    bitmap = bitmap,
                    isVisible = true
                )

                baseBitmaps[newLayer.id] = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                layerStrokes[newLayer.id] = mutableListOf()

                withContext(dispatchers.main) {
                    _uiState.update { it.copy(layers = it.layers + newLayer, activeLayerId = newLayer.id, activeTool = Tool.NONE) }
                    saveProject()
                }
            } else {
                withContext(dispatchers.main) {
                    Toast.makeText(context, "Invalid image format or missing project", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun onAddBlankLayer() {
        pushHistory()
        val projectId = _uiState.value.projectId ?: return
        viewModelScope.launch(dispatchers.io) {
            val metrics = context.resources.displayMetrics
            val width = metrics.widthPixels.takeIf { it > 0 } ?: 1080
            val height = metrics.heightPixels.takeIf { it > 0 } ?: 1920
            val blankBitmap = createBitmap(width, height)

            val filename = "layer_${UUID.randomUUID()}.png"
            val path = projectRepository.saveArtifact(projectId, filename, ImageUtils.bitmapToByteArray(blankBitmap))
            val localUri = "file://$path".toUri()

            withContext(dispatchers.main) {
                val sketchCount = _uiState.value.layers.count { it.isSketch }
                val newLayer = Layer(
                    id = UUID.randomUUID().toString(),
                    name = "Sketch ${sketchCount + 1}",
                    isSketch = true,
                    bitmap = blankBitmap,
                    uri = localUri
                )

                baseBitmaps[newLayer.id] = blankBitmap.copy(Bitmap.Config.ARGB_8888, false)
                layerStrokes[newLayer.id] = mutableListOf()

                _uiState.update { it.copy(layers = it.layers + newLayer, activeLayerId = newLayer.id, activeTool = Tool.NONE) }
                saveProject()
            }
        }
    }

    fun setBackgroundImage(uri: Uri) {
        val projectId = _uiState.value.projectId ?: return
        viewModelScope.launch(dispatchers.io) {
            _uiState.update { it.copy(isLoading = true) }
            val bitmap = ImageUtils.loadBitmapAsync(context, uri)
            if (bitmap != null) {
                val filename = "bg_${UUID.randomUUID()}.png"
                val path = projectRepository.saveArtifact(projectId, filename, ImageUtils.bitmapToByteArray(bitmap))
                val localUri = "file://$path".toUri()

                val project = projectRepository.currentProject.value
                if (project != null) {
                    projectRepository.updateProject(project.copy(backgroundImageUri = localUri))
                }

                withContext(dispatchers.main) {
                    _uiState.update { it.copy(backgroundBitmap = bitmap, isLoading = false) }
                }
            } else {
                withContext(dispatchers.main) {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    fun saveProject(name: String? = null) {
        viewModelScope.launch(dispatchers.io) {
            val currentProject = projectRepository.currentProject.value
            val updatedLayers = _uiState.value.layers.map { it.toOverlayLayer() }

            val projectToSave = if (currentProject == null) {
                GraffitiProject(name = name ?: "New Project", layers = updatedLayers)
            } else {
                currentProject.copy(
                    name = name ?: currentProject.name,
                    layers = updatedLayers,
                    lastModified = System.currentTimeMillis()
                )
            }

            if (currentProject == null) {
                projectRepository.createProject(projectToSave)
            } else {
                projectRepository.updateProject(projectToSave)
            }

            val mapPath = projectManager.getMapPath(context, projectToSave.id)
            slamManager.saveModel(mapPath)

            if (name != null) {
                exportProjectInternal(projectToSave)
            }
        }
    }

    private suspend fun exportProjectInternal(project: GraffitiProject) {
        val filename = "${project.name.replace(" ", "_")}_export.gxr"
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    projectManager.exportProjectToUri(context, project.id, uri)
                    withContext(dispatchers.main) {
                        Toast.makeText(context, "Project saved and exported to Downloads", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    throw java.io.IOException("Failed to create MediaStore entry")
                }
            } else {
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                val file = File(downloadsDir, filename)
                val uri = Uri.fromFile(file)
                projectManager.exportProjectToUri(context, project.id, uri)
                withContext(dispatchers.main) {
                    Toast.makeText(context, "Project saved and exported to ${file.absolutePath}", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            withContext(dispatchers.main) {
                Toast.makeText(context, "Project saved locally. Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun exportImage() {
        viewModelScope.launch(dispatchers.default) {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val metrics = context.resources.displayMetrics
                val compositeBitmap = exportManager.compositeLayers(
                    _uiState.value.layers,
                    metrics.widthPixels,
                    metrics.heightPixels
                )

                val success = saveBitmapToGallery(context, compositeBitmap)

                withContext(dispatchers.main) {
                    _uiState.update { it.copy(isLoading = false) }
                    if (success) {
                        Toast.makeText(context, "Image saved to gallery", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "Failed to save image", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(dispatchers.main) {
                    _uiState.update { it.copy(isLoading = false) }
                    Toast.makeText(context, "Export error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun toggleHandedness() = _uiState.update { it.copy(isRightHanded = !it.isRightHanded) }
    fun toggleDiagOverlay() = _uiState.update { it.copy(showDiagOverlay = !it.showDiagOverlay) }
    fun setActiveTool(tool: Tool) = _uiState.update { it.copy(activeTool = tool) }

    override fun onLayerActivated(id: String) = _uiState.update { it.copy(activeLayerId = id, activeTool = Tool.NONE) }

    override fun onLayerRemoved(id: String) {
        pushHistory()
        _uiState.update { state ->
            val updated = state.layers.filter { it.id != id }
            state.copy(layers = updated, activeLayerId = if (state.activeLayerId == id) updated.firstOrNull()?.id else state.activeLayerId, activeTool = Tool.NONE)
        }
        baseBitmaps.remove(id)
        layerStrokes.remove(id)
        saveProject()
    }

    override fun onLayerReordered(newOrder: List<String>) {
        pushHistory()
        _uiState.update { state ->
            val map = state.layers.associateBy { it.id }
            state.copy(layers = newOrder.mapNotNull { map[it] })
        }
        saveProject()
    }

    override fun onRemoveBackgroundClicked() {
        val state = _uiState.value
        val layerId = state.activeLayerId ?: return
        val layer = state.layers.find { it.id == layerId } ?: return
        val projectId = state.projectId ?: return
        val uri = layer.uri ?: return

        pushHistory()
        _uiState.update { it.copy(isLoading = true) }

        viewModelScope.launch(dispatchers.default) {
            val bitmap = ImageUtils.loadBitmapAsync(context, uri)
            if (bitmap != null) {
                val result = backgroundRemover.removeBackground(bitmap)
                result.onSuccess { fgBitmap ->
                    val path = projectRepository.saveArtifact(projectId, "bg_removed_${System.currentTimeMillis()}.png", ImageUtils.bitmapToByteArray(fgBitmap))
                    updateLayerUri(layerId, "file://$path".toUri())
                }
            }
            withContext(dispatchers.main) {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    override fun onLineDrawingClicked() {
        val state = _uiState.value
        val layerId = state.activeLayerId ?: return
        val layer = state.layers.find { it.id == layerId } ?: return
        val projectId = state.projectId ?: return
        val uri = layer.uri ?: return

        pushHistory()
        _uiState.update { it.copy(isLoading = true) }

        viewModelScope.launch(dispatchers.default) {
            val bitmap = ImageUtils.loadBitmapAsync(context, uri)
            if (bitmap != null) {
                val resultBitmap = com.hereliesaz.graffitixr.common.util.ImageProcessor.detectEdges(bitmap)
                if (resultBitmap != null) {
                    val path = projectRepository.saveArtifact(projectId, "line_art_${System.currentTimeMillis()}.png", ImageUtils.bitmapToByteArray(resultBitmap))
                    updateLayerUri(layerId, "file://$path".toUri())
                }
            }
            withContext(dispatchers.main) {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    private fun updateLayerUri(id: String, uri: Uri) {
        viewModelScope.launch(dispatchers.io) {
            val bitmap = ImageUtils.loadBitmapAsync(context, uri)
            withContext(dispatchers.main) {
                _uiState.update { state ->
                    val updatedLayers = state.layers.map {
                        if (it.id == id) {
                            bitmap?.let { bmp ->
                                baseBitmaps[id] = bmp.copy(Bitmap.Config.ARGB_8888, false)
                                layerStrokes[id] = mutableListOf()
                            }
                            it.copy(uri = uri, bitmap = bitmap)
                        } else it
                    }
                    state.copy(layers = updatedLayers)
                }
            }
            saveProject()
        }
    }

    override fun onMagicClicked() { pushHistory(); updateActiveLayer { it.copy(brightness = 0.1f, contrast = 1.2f, saturation = 1.1f) }; saveProject() }
    override fun onAdjustClicked() { _uiState.update { it.copy(activePanel = if (it.activePanel == EditorPanel.ADJUST) EditorPanel.NONE else EditorPanel.ADJUST) } }
    fun onBalanceClicked() { _uiState.update { it.copy(activePanel = if (it.activePanel == EditorPanel.COLOR) EditorPanel.NONE else EditorPanel.COLOR) } }
    override fun onDismissPanel() { _uiState.update { it.copy(activePanel = EditorPanel.NONE) } }

    fun onTransformGesture(pan: Offset, zoom: Float, rotationDelta: Float) {
        updateActiveLayer { layer ->
            var rx = layer.rotationX
            var ry = layer.rotationY
            var rz = layer.rotationZ

            when (_uiState.value.activeRotationAxis) {
                RotationAxis.X -> rx += rotationDelta
                RotationAxis.Y -> ry += rotationDelta
                RotationAxis.Z -> rz += rotationDelta
            }

            layer.copy(
                scale = layer.scale * zoom,
                offset = layer.offset + pan,
                rotationX = rx,
                rotationY = ry,
                rotationZ = rz
            )
        }
    }

    override fun onGestureEnd() { saveProject(); _uiState.update { it.copy(gestureInProgress = false) } }
    override fun onGestureStart() { pushHistory(); _uiState.update { it.copy(gestureInProgress = true) } }
    override fun toggleImageLock() { pushHistory(); updateActiveLayer { it.copy(isImageLocked = !it.isImageLocked) }; saveProject() }
    override fun onOpacityChanged(v: Float) = updateActiveLayer { it.copy(opacity = v) }
    override fun onBrightnessChanged(v: Float) = updateActiveLayer { it.copy(brightness = v) }
    override fun onContrastChanged(v: Float) = updateActiveLayer { it.copy(contrast = v) }
    override fun onSaturationChanged(v: Float) = updateActiveLayer { it.copy(saturation = v) }
    override fun onColorBalanceRChanged(v: Float) = updateActiveLayer { it.copy(colorBalanceR = v) }
    override fun onColorBalanceGChanged(v: Float) = updateActiveLayer { it.copy(colorBalanceG = v) }
    override fun onColorBalanceBChanged(v: Float) = updateActiveLayer { it.copy(colorBalanceB = v) }
    override fun onScaleChanged(s: Float) = updateActiveLayer { it.copy(scale = s) }
    override fun onOffsetChanged(o: Offset) = updateActiveLayer { it.copy(offset = it.offset + o) }

    override fun onRotationXChanged(d: Float) { updateActiveLayer { it.copy(rotationX = d) }; _uiState.update { it.copy(activeRotationAxis = RotationAxis.X) } }
    override fun onRotationYChanged(d: Float) { updateActiveLayer { it.copy(rotationY = d) }; _uiState.update { it.copy(activeRotationAxis = RotationAxis.Y) } }
    override fun onRotationZChanged(d: Float) { updateActiveLayer { it.copy(rotationZ = d) }; _uiState.update { it.copy(activeRotationAxis = RotationAxis.Z) } }

    override fun onCycleRotationAxis() {
        val next = when (_uiState.value.activeRotationAxis) { RotationAxis.X -> RotationAxis.Y; RotationAxis.Y -> RotationAxis.Z; RotationAxis.Z -> RotationAxis.X }
        _uiState.update { it.copy(activeRotationAxis = next, showRotationAxisFeedback = true) }
    }

    override fun onAdjustmentStart() { pushHistory(); _uiState.update { it.copy(gestureInProgress = true) } }

    override fun onAdjustmentEnd() {
        _uiState.update { it.copy(gestureInProgress = false) }
        saveProject()
    }

    override fun setLayerTransform(scale: Float, offset: Offset, rx: Float, ry: Float, rz: Float) {
        updateActiveLayer { it.copy(scale = scale, offset = offset, rotationX = rx, rotationY = ry, rotationZ = rz) }
        saveProject()
    }

    override fun onLayerWarpChanged(layerId: String, mesh: List<Float>) {
        _uiState.update { state -> state.copy(layers = state.layers.map { if (it.id == layerId) it.copy(warpMesh = mesh) else it }) }
        saveProject()
    }

    override fun copyLayerModifications(id: String) { copiedLayerState = _uiState.value.layers.find { it.id == id } }

    override fun pasteLayerModifications(id: String) {
        val source = copiedLayerState ?: return
        pushHistory()
        _uiState.update { state -> state.copy(layers = state.layers.map { if (it.id == id) it.copy(opacity = source.opacity, brightness = source.brightness, contrast = source.contrast, saturation = source.saturation, colorBalanceR = source.colorBalanceR, colorBalanceG = source.colorBalanceG, colorBalanceB = source.colorBalanceB, blendMode = source.blendMode, warpMesh = source.warpMesh) else it }) }
        saveProject()
    }

    override fun onCycleBlendMode() {
        pushHistory()
        updateActiveLayer { layer ->
            val domainModes = BlendMode.entries.toTypedArray()
            val currentDomainMode = layer.blendMode.toModelBlendMode()
            val nextIndex = (domainModes.indexOf(currentDomainMode) + 1) % domainModes.size
            layer.copy(blendMode = domainModes[nextIndex].toComposeBlendMode())
        }
        saveProject()
    }

    override fun onLayerDuplicated(id: String) {
        val layer = _uiState.value.layers.find { it.id == id } ?: return
        val projectId = _uiState.value.projectId ?: return
        pushHistory()

        viewModelScope.launch(dispatchers.io) {
            val currentBitmap = layer.bitmap
            val newBitmap = currentBitmap?.copy(currentBitmap.config ?: Bitmap.Config.ARGB_8888, true)
            val newUri = newBitmap?.let { bmp ->
                val filename = "layer_dup_${UUID.randomUUID()}.png"
                val path = projectRepository.saveArtifact(projectId, filename, ImageUtils.bitmapToByteArray(bmp))
                "file://$path".toUri()
            } ?: layer.uri

            val duplicated = layer.copy(
                id = UUID.randomUUID().toString(),
                name = "${layer.name} Copy",
                bitmap = newBitmap,
                uri = newUri
            )

            newBitmap?.let { bmp ->
                baseBitmaps[duplicated.id] = bmp.copy(Bitmap.Config.ARGB_8888, false)
                layerStrokes[duplicated.id] = mutableListOf()
            }

            withContext(dispatchers.main) {
                _uiState.update { it.copy(layers = it.layers + duplicated, activeLayerId = duplicated.id, activeTool = Tool.NONE) }
                saveProject()
            }
        }
    }

    override fun onLayerRenamed(id: String, name: String) {
        pushHistory()
        _uiState.update { state -> state.copy(layers = state.layers.map { if (it.id == id) it.copy(name = name) else it }) }
        saveProject()
    }

    private fun updateActiveLayer(transform: (Layer) -> Layer) {
        _uiState.update { state -> val id = state.activeLayerId ?: return@update state; state.copy(layers = state.layers.map { if (it.id == id) transform(it) else it }) }
    }

    override fun onFeedbackShown() { _uiState.update { it.copy(showRotationAxisFeedback = false) } }
    override fun onDoubleTapHintDismissed() {}
    override fun onOnboardingComplete(mode: Any) {}

    override fun onDrawingPathFinished(path: List<Offset>, canvasSize: IntSize) {
        applyStrokeToActiveLayer(path, canvasSize)
    }

    override fun onColorClicked() {
        _uiState.update { it.copy(showColorPicker = true) }
    }

    override fun setBrushSize(size: Float) {
        _uiState.update { it.copy(brushSize = size.coerceIn(1f, 200f)) }
    }

    override fun setActiveColor(color: Color) {
        _uiState.update { it.copy(activeColor = color, showColorPicker = false) }
    }

    override fun adjustColorLightness(delta: Float) {
        _uiState.update { state ->
            val c = state.activeColor
            val hsv = FloatArray(3)
            android.graphics.Color.RGBToHSV(
                (c.red * 255).toInt(),
                (c.green * 255).toInt(),
                (c.blue * 255).toInt(),
                hsv
            )
            hsv[2] = (hsv[2] + delta).coerceIn(0f, 1f)
            val newArgb = android.graphics.Color.HSVToColor(hsv)
            val newColor = Color(newArgb).copy(alpha = c.alpha)
            state.copy(activeColor = newColor)
        }
    }

    override fun onColorPickerDismissed() {
        _uiState.update { it.copy(showColorPicker = false) }
    }

    fun setLayers(layers: List<Layer>) {
        _uiState.update { it.copy(layers = layers) }
        saveProject()
    }
}