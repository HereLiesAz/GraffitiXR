// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorViewModel.kt
package com.hereliesaz.graffitixr.feature.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.common.DispatcherProvider
import com.hereliesaz.graffitixr.common.model.*
import com.hereliesaz.graffitixr.common.util.ImageUtils
import com.hereliesaz.graffitixr.common.util.saveBitmapToGallery
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import com.hereliesaz.graffitixr.data.ProjectManager
import com.hereliesaz.graffitixr.feature.editor.export.ExportManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import androidx.core.net.toUri
import androidx.core.graphics.createBitmap
import com.hereliesaz.graffitixr.feature.editor.stencil.StencilPrintEngine
import com.hereliesaz.graffitixr.feature.editor.stencil.StencilProcessor
import com.hereliesaz.graffitixr.feature.editor.stencil.StencilProgress
import com.hereliesaz.graffitixr.feature.editor.util.ImageProcessor
import com.hereliesaz.graffitixr.common.util.SketchProcessor
import kotlinx.coroutines.flow.collect

data class StrokeCommand(
    val path: List<Offset>,
    val canvasSize: IntSize,
    val tool: Tool,
    val brushSize: Float,
    val brushColor: Int,
    val intensity: Float,
    val feathering: Float = 0f,
    val layerScale: Float = 1f,
    val layerOffset: Offset = Offset.Zero,
    val layerRotationZ: Float = 0f
)

sealed class EditCommand {
    data class PropertyChange(val oldLayers: List<Layer>) : EditCommand()
    data class Draw(val layerId: String, val command: StrokeCommand) : EditCommand()
}

@HiltViewModel
class EditorViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val settingsRepository: SettingsRepository,
    private val projectManager: ProjectManager,
    private val exportManager: ExportManager,
    @ApplicationContext private val context: Context,
    private val subjectIsolator: SubjectIsolator,
    private val stencilProcessor: StencilProcessor,
    private val stencilPrintEngine: StencilPrintEngine,
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
    private var anchorHalfExtentMeters: Pair<Float, Float>? = null

    private var rawSegmentationConfidence: FloatArray? = null
    private var segmentationSourceBitmap: Bitmap? = null
    private var segmentationTargetLayerId: String? = null

    // Real-time stroke state — valid only between onStrokeStart and onStrokeEnd.
    private var strokeWorkingBitmap: Bitmap? = null
    private var strokeWorkingCanvas: Canvas? = null
    private var strokePaint: Paint? = null
    private var strokePrevBitmapPoint: Offset? = null
    private var strokeCollectedPoints: MutableList<Offset> = mutableListOf()
    private var strokeLayerId: String? = null
    private var strokeCanvasW: Int = 0
    private var strokeCanvasH: Int = 0
    // Layer transform snapshot captured at stroke start — held constant for the whole stroke.
    private var strokeLayerScale: Float = 1f
    private var strokeLayerOffset: Offset = Offset.Zero
    private var strokeLayerRotationZ: Float = 0f

    // Liquify live-preview state — valid only between onStrokeStart and onStrokeEnd for LIQUIFY.
    private var liquifyJob: kotlinx.coroutines.Job? = null
    private var liquifyOriginalBitmap: Bitmap? = null

    init {
        viewModelScope.launch(dispatchers.main) {
            settingsRepository.backgroundColor.collect { argb ->
                _uiState.update { it.copy(canvasBackground = Color(argb.toLong() and 0xFFFFFFFFL)) }
            }
        }

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
                    stroke.path, stroke.canvasSize.width, stroke.canvasSize.height, currentBitmap.width, currentBitmap.height,
                    stroke.layerScale, stroke.layerOffset, stroke.layerRotationZ
                )
                currentBitmap = com.hereliesaz.graffitixr.feature.editor.util.ImageProcessor.applyToolToBitmap(
                    currentBitmap, mapped, stroke.tool, stroke.brushSize, stroke.brushColor, stroke.intensity, true, stroke.feathering
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
                activeBitmap.width, activeBitmap.height,
                command.layerScale, command.layerOffset, command.layerRotationZ
            )

            val newBitmap = com.hereliesaz.graffitixr.feature.editor.util.ImageProcessor.applyToolToBitmap(
                activeBitmap, mappedStroke, command.tool, command.brushSize, command.brushColor, command.intensity, false, command.feathering
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

            val mapPath = projectManager.getMapPath(context, projectToSave.id)
            val cloudPointsPath = projectManager.getCloudPointsPath(context, projectToSave.id)

            // Persist SLAM world before writing the manifest so the paths are valid.
            slamManager.saveModel(mapPath)

            // Record the SLAM world paths in the .gxr manifest.
            val manifestToSave = projectToSave.copy(
                mapPath = mapPath,
                cloudPointsPath = cloudPointsPath
            )

            if (currentProject == null) {
                projectRepository.createProject(manifestToSave)
            } else {
                projectRepository.updateProject(manifestToSave)
            }

            if (name != null) {
                exportProjectInternal(manifestToSave)
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
                val result = subjectIsolator.isolate(bitmap)
                result.onSuccess { isolationResult ->
                    val path = projectRepository.saveArtifact(projectId, "bg_removed_${System.currentTimeMillis()}.png", ImageUtils.bitmapToByteArray(isolationResult.isolatedBitmap))
                    updateLayerUri(layerId, "file://$path".toUri())
                    rawSegmentationConfidence = isolationResult.rawConfidence
                    segmentationSourceBitmap = bitmap
                    segmentationTargetLayerId = layerId
                    withContext(dispatchers.main) {
                        _uiState.update { it.copy(isSegmenting = true, segmentationInfluence = 0.5f) }
                    }
                }
            }
            withContext(dispatchers.main) {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun setSegmentationInfluence(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        _uiState.update { it.copy(segmentationInfluence = clamped) }

        val confidence = rawSegmentationConfidence ?: return
        val source = segmentationSourceBitmap ?: return
        val targetId = segmentationTargetLayerId ?: return

        viewModelScope.launch(dispatchers.default) {
            val newBitmap = subjectIsolator.applyConfidenceThreshold(source, confidence, clamped, 0.1f)
            withContext(dispatchers.main) {
                _uiState.update { state ->
                    state.copy(
                        layers = state.layers.map { layer ->
                            if (layer.id == targetId) layer.copy(bitmap = newBitmap) else layer
                        }
                    )
                }
            }
        }
    }

    fun dismissSegmentationSlider() {
        rawSegmentationConfidence = null
        segmentationSourceBitmap = null
        segmentationTargetLayerId = null
        _uiState.update { it.copy(isSegmenting = false) }
    }

    override fun onSketchClicked() {
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
                val bg = state.canvasBackground
                val penArgb = android.graphics.Color.argb(
                    255,
                    (255 * (1f - bg.red)).toInt().coerceIn(0, 255),
                    (255 * (1f - bg.green)).toInt().coerceIn(0, 255),
                    (255 * (1f - bg.blue)).toInt().coerceIn(0, 255)
                )
                val sketchBitmap = SketchProcessor.sketchEffect(bitmap, state.sketchThickness, penArgb)
                if (sketchBitmap != null) {
                    val path = projectRepository.saveArtifact(
                        projectId,
                        "sketch_${System.currentTimeMillis()}.png",
                        ImageUtils.bitmapToByteArray(sketchBitmap)
                    )
                    val sketchUri = "file://$path".toUri()
                    val sketchLayer = Layer(
                        id = java.util.UUID.randomUUID().toString(),
                        name = "Outline – ${layer.name}",
                        uri = sketchUri,
                        isSketch = true,
                        isLinked = true,
                        blendMode = androidx.compose.ui.graphics.BlendMode.SrcOver
                    )
                    withContext(dispatchers.main) {
                        _uiState.update { s ->
                            val idx = s.layers.indexOfFirst { it.id == layerId }
                            if (idx < 0) return@update s
                            val newLayers = s.layers.toMutableList().also { list ->
                                // Mark source layer as linked
                                list[idx] = list[idx].copy(isLinked = true)
                                // Insert sketch layer immediately above source
                                list.add(idx + 1, sketchLayer)
                            }
                            s.copy(layers = newLayers, isLoading = false)
                        }
                    }
                    // Load the bitmap into the sketch layer so it renders immediately
                    updateLayerUri(sketchLayer.id, sketchUri)
                    return@launch
                }
            }
            withContext(dispatchers.main) {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    override fun onSketchThicknessChanged(thickness: Int) {
        _uiState.update { it.copy(sketchThickness = thickness.coerceIn(1, 20)) }
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

    fun setAnchorExtent(halfW: Float, halfH: Float) {
        anchorHalfExtentMeters = Pair(halfW, halfH)
    }

    private fun fitActiveLayerToAnchor(halfW: Float, halfH: Float) {
        val state = _uiState.value
        val layer = state.layers.find { it.id == state.activeLayerId } ?: return
        val bmp = layer.bitmap ?: return
        // QUAD_HALF_EXTENT = 5.0f (matches OverlayRenderer.QUAD_HALF_EXTENT)
        // The composite canvas is 2048×2048. Scale to fill 80% of the anchor extent.
        val scaleW = halfW * 0.8f * 2048f / (bmp.width * 5.0f)
        val scaleH = halfH * 0.8f * 2048f / (bmp.height * 5.0f)
        val scale = minOf(scaleW, scaleH).coerceIn(0.05f, 20f)
        updateActiveLayer { it.copy(scale = scale, offset = Offset.Zero, rotationX = 0f, rotationY = 0f, rotationZ = 0f) }
    }

    override fun onMagicClicked() {
        pushHistory()
        val extent = anchorHalfExtentMeters
        if (extent != null) {
            fitActiveLayerToAnchor(extent.first, extent.second)
        } else {
            updateActiveLayer { it.copy(brightness = 0.1f, contrast = 1.2f, saturation = 1.1f) }
        }
        saveProject()
    }
    override fun onAdjustClicked() { _uiState.update { it.copy(activePanel = if (it.activePanel == EditorPanel.ADJUST) EditorPanel.NONE else EditorPanel.ADJUST) } }
    fun onBalanceClicked() { _uiState.update { it.copy(activePanel = if (it.activePanel == EditorPanel.COLOR) EditorPanel.NONE else EditorPanel.COLOR) } }
    override fun onDismissPanel() { _uiState.update { it.copy(activePanel = EditorPanel.NONE) } }

    fun onTransformGesture(pan: Offset, zoom: Float, rotationDelta: Float) {
        val activeId = _uiState.value.activeLayerId ?: return
        val axis = _uiState.value.activeRotationAxis
        updateLinkedGroup(activeId) { layer ->
            val rx = if (axis == RotationAxis.X) layer.rotationX + rotationDelta else layer.rotationX
            val ry = if (axis == RotationAxis.Y) layer.rotationY + rotationDelta else layer.rotationY
            val rz = if (axis == RotationAxis.Z) layer.rotationZ + rotationDelta else layer.rotationZ
            layer.copy(scale = layer.scale * zoom, offset = layer.offset + pan, rotationX = rx, rotationY = ry, rotationZ = rz)
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

    /** Returns the IDs of all layers in the same link-group as [layerId].
     *  A group is a contiguous run where each layer above the bottom has isLinked = true. */
    private fun getLinkedGroupIds(layerId: String): Set<String> {
        val layers = _uiState.value.layers
        val idx = layers.indexOfFirst { it.id == layerId }
        if (idx < 0) return setOf(layerId)
        // Walk down to find group bottom (first layer in run whose isLinked is false)
        var bottom = idx
        while (bottom > 0 && layers[bottom].isLinked) bottom--
        // Walk up to find group top (last consecutive layer whose next has isLinked = true)
        var top = idx
        while (top + 1 < layers.size && layers[top + 1].isLinked) top++
        return layers.subList(bottom, top + 1).map { it.id }.toSet()
    }

    private fun updateLinkedGroup(activeId: String, transform: (Layer) -> Layer) {
        val groupIds = getLinkedGroupIds(activeId)
        _uiState.update { state -> state.copy(layers = state.layers.map { if (it.id in groupIds) transform(it) else it }) }
    }

    override fun onFeedbackShown() { _uiState.update { it.copy(showRotationAxisFeedback = false) } }
    override fun onDoubleTapHintDismissed() {}
    override fun onOnboardingComplete(mode: Any) {}

    // Kept for interface compliance; no longer called (DrawingCanvas now uses the three-phase API).
    override fun onDrawingPathFinished(path: List<Offset>, canvasSize: IntSize) {}

    /** Called when the user first touches the canvas. Prepares a mutable working bitmap for
     *  incremental real-time rendering (all tools except Liquify). */
    fun onStrokeStart(startPoint: Offset, canvasSize: IntSize) {
        val state = _uiState.value
        if (state.activeTool == Tool.NONE) return
        val layerId = state.activeLayerId ?: return
        val layer = state.layers.find { it.id == layerId } ?: return
        val originalBitmap = layer.bitmap ?: return

        strokeCollectedPoints = mutableListOf(startPoint)
        strokeLayerId = layerId
        strokeCanvasW = canvasSize.width
        strokeCanvasH = canvasSize.height
        strokeLayerScale = layer.scale
        strokeLayerOffset = layer.offset
        strokeLayerRotationZ = layer.rotationZ

        if (state.activeTool == Tool.LIQUIFY) {
            // Store the original bitmap so live-preview warps can be applied from a clean copy.
            liquifyOriginalBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, false)
            _uiState.update { it.copy(liveStrokeLayerId = layerId) }
            return
        }

        val tool = state.activeTool
        val argb = state.activeColor.toArgb()
        val brushSize = state.brushSize
        val feathering = state.brushFeathering

        // Copy the bitmap on a background thread (can be ~10-50 ms for large images).
        // After the copy is done, replay ALL points collected so far (including any that
        // arrived while the copy was in flight) so no input is lost.
        viewModelScope.launch(dispatchers.default) {
            val workBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
            val workCanvas = Canvas(workBitmap)
            val paint = buildStrokePaint(tool, argb, brushSize, feathering)

            // Snapshot the collected points at this moment — may include points that arrived
            // during the bitmap-copy phase.
            val catchUpPoints = strokeCollectedPoints.toList()
            val mappedAll = ImageProcessor.mapScreenToBitmap(
                catchUpPoints, canvasSize.width, canvasSize.height, workBitmap.width, workBitmap.height,
                strokeLayerScale, strokeLayerOffset, strokeLayerRotationZ
            )

            if (mappedAll.size == 1) {
                workCanvas.drawPoint(mappedAll[0].x, mappedAll[0].y, paint)
            } else {
                val seg = android.graphics.Path()
                seg.moveTo(mappedAll[0].x, mappedAll[0].y)
                for (i in 1 until mappedAll.size) {
                    seg.lineTo(mappedAll[i].x, mappedAll[i].y)
                }
                workCanvas.drawPath(seg, paint)
            }

            val lastMapped = mappedAll.last()

            withContext(dispatchers.main) {
                strokeWorkingBitmap = workBitmap
                strokeWorkingCanvas = workCanvas
                strokePaint = paint
                strokePrevBitmapPoint = lastMapped
                _uiState.update { it.copy(
                    liveStrokeLayerId = layerId,
                    liveStrokeBitmap = workBitmap,
                    liveStrokeVersion = it.liveStrokeVersion + catchUpPoints.size
                )}
            }
        }
    }

    /** Called for every drag update. Draws only the new segment onto the working bitmap. */
    fun onStrokePoint(currentPoint: Offset) {
        strokeCollectedPoints.add(currentPoint)

        // Liquify live preview: cancel any pending warp job and start a fresh one from the
        // original bitmap so each drag frame shows the full accumulated warp.
        if (_uiState.value.activeTool == Tool.LIQUIFY) {
            val layerId = strokeLayerId ?: return
            val origBmp = liquifyOriginalBitmap ?: return
            val points = strokeCollectedPoints.toList()
            val canvasW = strokeCanvasW
            val canvasH = strokeCanvasH
            val brushSize = _uiState.value.brushSize
            val argb = _uiState.value.activeColor.toArgb()
            val capturedScale = strokeLayerScale
            val capturedOffset = strokeLayerOffset
            val capturedRotZ = strokeLayerRotationZ
            val feathering = _uiState.value.brushFeathering

            liquifyJob?.cancel()
            liquifyJob = viewModelScope.launch(dispatchers.default) {
                val warpBitmap = origBmp.copy(Bitmap.Config.ARGB_8888, true)
                val mappedPoints = ImageProcessor.mapScreenToBitmap(
                    points, canvasW, canvasH, warpBitmap.width, warpBitmap.height,
                    capturedScale, capturedOffset, capturedRotZ
                )
                ImageProcessor.applyToolToBitmap(
                    warpBitmap, mappedPoints, Tool.LIQUIFY, brushSize, argb, 0.5f, true, feathering
                )
                if (isActive) {
                    withContext(dispatchers.main) {
                        _uiState.update { it.copy(
                            liveStrokeBitmap = warpBitmap,
                            liveStrokeVersion = it.liveStrokeVersion + 1
                        )}
                    }
                }
            }
            return
        }

        val canvas = strokeWorkingCanvas ?: return
        val paint = strokePaint ?: return
        val prev = strokePrevBitmapPoint ?: return
        val workBitmap = strokeWorkingBitmap ?: return

        val mapped = ImageProcessor.mapScreenToBitmap(
            listOf(currentPoint), strokeCanvasW, strokeCanvasH, workBitmap.width, workBitmap.height,
            strokeLayerScale, strokeLayerOffset, strokeLayerRotationZ
        ).first()

        val seg = Path()
        seg.moveTo(prev.x, prev.y)
        seg.lineTo(mapped.x, mapped.y)
        canvas.drawPath(seg, paint)
        strokePrevBitmapPoint = mapped

        _uiState.update { it.copy(liveStrokeVersion = it.liveStrokeVersion + 1) }
    }

    /** Called when the user lifts their finger. Finalizes the stroke into the layer and undo history. */
    fun onStrokeEnd() {
        val state = _uiState.value
        val layerId = strokeLayerId ?: return
        val layer = state.layers.find { it.id == layerId } ?: return
        val points = strokeCollectedPoints.toList()
        val canvasW = strokeCanvasW
        val canvasH = strokeCanvasH

        val capturedScale = strokeLayerScale
        val capturedOffset = strokeLayerOffset
        val capturedRotationZ = strokeLayerRotationZ

        if (state.activeTool == Tool.LIQUIFY || strokeWorkingBitmap == null) {
            // Liquify (or a stroke so fast the background copy hadn't finished):
            // fall back to the full whole-stroke approach.
            val bitmap = layer.bitmap ?: return
            val command = StrokeCommand(
                path = points,
                canvasSize = IntSize(canvasW, canvasH),
                tool = state.activeTool,
                brushSize = state.brushSize,
                brushColor = state.activeColor.toArgb(),
                intensity = 0.5f,
                feathering = state.brushFeathering,
                layerScale = capturedScale,
                layerOffset = capturedOffset,
                layerRotationZ = capturedRotationZ
            )
            processNewStroke(layerId, bitmap, command, layer)
        } else {
            // Real-time path: the working bitmap already contains the complete stroke.
            val workBitmap = strokeWorkingBitmap!!
            val command = StrokeCommand(
                path = points,
                canvasSize = IntSize(canvasW, canvasH),
                tool = state.activeTool,
                brushSize = state.brushSize,
                brushColor = state.activeColor.toArgb(),
                intensity = 0.5f,
                feathering = state.brushFeathering,
                layerScale = capturedScale,
                layerOffset = capturedOffset,
                layerRotationZ = capturedRotationZ
            )

            // Add stroke to history for undo/redo replay.
            val currentStrokes = layerStrokes[layerId] ?: mutableListOf()
            currentStrokes.add(command)
            layerStrokes[layerId] = currentStrokes
            undoStack.addLast(EditCommand.Draw(layerId, command))
            if (undoStack.size > maxStackSize) undoStack.removeFirst()
            redoStack.clear()
            updateHistoryCounts()

            // Commit: working bitmap becomes the displayed layer bitmap.
            _uiState.update { s ->
                s.copy(
                    layers = s.layers.map { if (it.id == layerId) it.copy(bitmap = workBitmap) else it },
                    liveStrokeLayerId = null,
                    liveStrokeBitmap = null
                )
            }
            scheduleDiskSave(layerId, workBitmap, layer.uri)
        }

        // Clear stroke working state.
        strokeWorkingBitmap = null
        strokeWorkingCanvas = null
        strokePaint = null
        strokePrevBitmapPoint = null
        strokeCollectedPoints = mutableListOf()
        strokeLayerId = null

        // Clean up Liquify live-preview state.
        liquifyJob?.cancel()
        liquifyJob = null
        liquifyOriginalBitmap = null
    }

    private fun buildStrokePaint(tool: Tool, argbColor: Int, brushSize: Float, feathering: Float): Paint =
        Paint().apply {
            strokeWidth = brushSize
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
            when (tool) {
                Tool.BRUSH -> {
                    color = argbColor
                    if (feathering > 0f) maskFilter = BlurMaskFilter(brushSize * feathering * 0.5f, BlurMaskFilter.Blur.NORMAL)
                }
                Tool.ERASER -> {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                    if (feathering > 0f) maskFilter = BlurMaskFilter(brushSize * feathering * 0.5f, BlurMaskFilter.Blur.NORMAL)
                }
                Tool.BLUR -> {
                    maskFilter = BlurMaskFilter(brushSize * 0.5f, BlurMaskFilter.Blur.NORMAL)
                    alpha = 150
                }
                Tool.BURN -> {
                    color = android.graphics.Color.BLACK
                    alpha = (255 * 0.3f).toInt().coerceIn(0, 255)
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DARKEN)
                }
                Tool.DODGE -> {
                    color = android.graphics.Color.WHITE
                    alpha = (255 * 0.3f).toInt().coerceIn(0, 255)
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.LIGHTEN)
                }
                Tool.HEAL -> {
                    color = argbColor
                    alpha = 128
                }
                else -> {}
            }
        }

    override fun onColorClicked() {
        _uiState.update { it.copy(showColorPicker = true) }
    }

    override fun setBrushSize(size: Float) {
        _uiState.update { it.copy(brushSize = size.coerceIn(1f, 200f)) }
    }

    fun setBrushFeathering(amount: Float) {
        _uiState.update { it.copy(brushFeathering = amount.coerceIn(0f, 1f)) }
    }

    override fun setActiveColor(color: Color) {
        _uiState.update { it.copy(activeColor = color, showColorPicker = false) }
    }

    override fun adjustColorLightness(delta: Float) {
        adjustColorHSV(lightnessDelta = delta, saturationDelta = 0f)
    }

    override fun adjustColorHSV(lightnessDelta: Float, saturationDelta: Float) {
        _uiState.update { state ->
            val c = state.activeColor
            val hsv = FloatArray(3)
            android.graphics.Color.RGBToHSV(
                (c.red * 255).toInt(),
                (c.green * 255).toInt(),
                (c.blue * 255).toInt(),
                hsv
            )
            hsv[1] = (hsv[1] + saturationDelta).coerceIn(0f, 1f)
            hsv[2] = (hsv[2] + lightnessDelta).coerceIn(0f, 1f)
            val newArgb = android.graphics.Color.HSVToColor(hsv)
            state.copy(activeColor = Color(newArgb).copy(alpha = c.alpha))
        }
    }

    override fun onColorPickerDismissed() {
        _uiState.update { it.copy(showColorPicker = false) }
    }

    override fun onFlattenAllLayers() {
        val projectId = _uiState.value.projectId ?: return
        pushHistory()
        viewModelScope.launch(dispatchers.default) {
            val metrics = context.resources.displayMetrics
            val w = metrics.widthPixels.takeIf { it > 0 } ?: 1080
            val h = metrics.heightPixels.takeIf { it > 0 } ?: 1920
            val composite = exportManager.compositeLayers(_uiState.value.layers, w, h)

            val filename = "flattened_${UUID.randomUUID()}.png"
            val path = projectRepository.saveArtifact(projectId, filename, ImageUtils.bitmapToByteArray(composite))
            val localUri = "file://$path".toUri()

            val flatLayer = Layer(
                id = UUID.randomUUID().toString(),
                name = "Flattened",
                uri = localUri,
                bitmap = composite
            )

            withContext(dispatchers.main) {
                _uiState.value.layers.forEach { baseBitmaps.remove(it.id); layerStrokes.remove(it.id) }
                baseBitmaps[flatLayer.id] = composite.copy(Bitmap.Config.ARGB_8888, false)
                layerStrokes[flatLayer.id] = mutableListOf()
                _uiState.update { it.copy(layers = listOf(flatLayer), activeLayerId = flatLayer.id, activeTool = Tool.NONE) }
                saveProject()
            }
        }
    }

    override fun onToggleLinkLayer(layerId: String) {
        pushHistory()
        _uiState.update { state ->
            state.copy(layers = state.layers.map { if (it.id == layerId) it.copy(isLinked = !it.isLinked) else it })
        }
        saveProject()
    }

    fun setLayers(layers: List<Layer>) {
        _uiState.update { it.copy(layers = layers) }
        saveProject()
    }

    override fun onAddTextLayer() {
        pushHistory()
        val projectId = _uiState.value.projectId ?: return
        val textCount = _uiState.value.layers.count { it.textParams != null }
        val defaultParams = TextLayerParams(text = "Text ${textCount + 1}")
        viewModelScope.launch(dispatchers.io) {
            val metrics = context.resources.displayMetrics
            val widthPx = metrics.widthPixels.takeIf { it > 0 } ?: 1080
            val heightPx = metrics.heightPixels.takeIf { it > 0 } ?: 1920
            val density = metrics.density

            val typeface = GoogleFontCache.getTypeface(context, defaultParams.fontName, defaultParams.isBold, defaultParams.isItalic)
            val bitmap = TextRasterizer.rasterize(defaultParams, widthPx, heightPx, density, typeface)

            val filename = "text_layer_${UUID.randomUUID()}.png"
            val path = projectRepository.saveArtifact(projectId, filename, ImageUtils.bitmapToByteArray(bitmap))
            val localUri = "file://$path".toUri()

            val newLayer = Layer(
                id = UUID.randomUUID().toString(),
                name = "Text${textCount + 1}",
                uri = localUri,
                bitmap = bitmap,
                isVisible = true,
                textParams = defaultParams
            )
            baseBitmaps[newLayer.id] = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            layerStrokes[newLayer.id] = mutableListOf()

            withContext(dispatchers.main) {
                _uiState.update { it.copy(layers = it.layers + newLayer, activeLayerId = newLayer.id, activeTool = Tool.NONE) }
                saveProject()
            }
        }
    }

    private fun rerasterizeTextLayer(layerId: String, params: TextLayerParams) {
        viewModelScope.launch(dispatchers.io) {
            val metrics = context.resources.displayMetrics
            val widthPx = metrics.widthPixels.takeIf { it > 0 } ?: 1080
            val heightPx = metrics.heightPixels.takeIf { it > 0 } ?: 1920
            val density = metrics.density

            val typeface = GoogleFontCache.getTypeface(context, params.fontName, params.isBold, params.isItalic)
            val bitmap = TextRasterizer.rasterize(params, widthPx, heightPx, density, typeface)

            val layer = _uiState.value.layers.find { it.id == layerId } ?: return@launch
            val uri = layer.uri
            if (uri != null) {
                try {
                    val file = java.io.File(uri.path ?: return@launch)
                    java.io.FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
                } catch (_: Exception) {}
            }

            baseBitmaps[layerId] = bitmap.copy(Bitmap.Config.ARGB_8888, false)

            withContext(dispatchers.main) {
                _uiState.update { state ->
                    state.copy(layers = state.layers.map {
                        if (it.id == layerId) it.copy(bitmap = bitmap, textParams = params) else it
                    })
                }
            }
        }
    }

    override fun onTextContentChanged(layerId: String, text: String) {
        val layer = _uiState.value.layers.find { it.id == layerId } ?: return
        val params = layer.textParams ?: return
        pushHistory()
        val updated = params.copy(text = text)
        rerasterizeTextLayer(layerId, updated)
        viewModelScope.launch(dispatchers.main) { saveProject() }
    }

    override fun onTextFontChanged(layerId: String, fontName: String) {
        val layer = _uiState.value.layers.find { it.id == layerId } ?: return
        val params = layer.textParams ?: return
        pushHistory()
        val updated = params.copy(fontName = fontName)
        rerasterizeTextLayer(layerId, updated)
        viewModelScope.launch(dispatchers.main) { saveProject() }
    }

    override fun onTextSizeChanged(layerId: String, sizeDp: Float) {
        val layer = _uiState.value.layers.find { it.id == layerId } ?: return
        val params = layer.textParams ?: return
        val updated = params.copy(fontSizeDp = sizeDp.coerceIn(8f, 300f))
        rerasterizeTextLayer(layerId, updated)
    }

    override fun onTextColorChanged(layerId: String, colorArgb: Int) {
        val layer = _uiState.value.layers.find { it.id == layerId } ?: return
        val params = layer.textParams ?: return
        pushHistory()
        val updated = params.copy(colorArgb = colorArgb)
        rerasterizeTextLayer(layerId, updated)
        viewModelScope.launch(dispatchers.main) { saveProject() }
    }

    override fun onTextKerningChanged(layerId: String, letterSpacingEm: Float) {
        val layer = _uiState.value.layers.find { it.id == layerId } ?: return
        val params = layer.textParams ?: return
        val updated = params.copy(letterSpacingEm = letterSpacingEm.coerceIn(-0.2f, 1f))
        rerasterizeTextLayer(layerId, updated)
    }

    override fun onTextStyleChanged(layerId: String, isBold: Boolean, isItalic: Boolean, hasOutline: Boolean, hasDropShadow: Boolean) {
        val layer = _uiState.value.layers.find { it.id == layerId } ?: return
        val params = layer.textParams ?: return
        pushHistory()
        val updated = params.copy(isBold = isBold, isItalic = isItalic, hasOutline = hasOutline, hasDropShadow = hasDropShadow)
        rerasterizeTextLayer(layerId, updated)
        viewModelScope.launch(dispatchers.main) { saveProject() }
    }

    override fun onGenerateStencil(layerId: String) {
        val state = _uiState.value
        val sourceLayer = state.layers.find { it.id == layerId } ?: return
        val projectId = state.projectId ?: return

        pushHistory()
        _uiState.update { it.copy(isLoading = true) }

        viewModelScope.launch(dispatchers.default) {
            // 1. Identify linked group and composite
            val groupIds = getLinkedGroupIds(layerId)
            val groupLayers = state.layers.filter { it.id in groupIds }
            
            val metrics = context.resources.displayMetrics
            val w = metrics.widthPixels.takeIf { it > 0 } ?: 1080
            val h = metrics.heightPixels.takeIf { it > 0 } ?: 1920
            
            val composite = exportManager.compositeLayers(groupLayers, w, h)
            
            // 2. Determine next stencil type
            val existingStencils = groupLayers.filter { it.stencilType != null }
            val (nextType, totalCount) = when {
                existingStencils.none { it.stencilType == StencilLayerType.SILHOUETTE } -> 
                    StencilLayerType.SILHOUETTE to StencilLayerCount.ONE
                existingStencils.none { it.stencilType == StencilLayerType.HIGHLIGHT } -> 
                    StencilLayerType.HIGHLIGHT to StencilLayerCount.TWO
                existingStencils.none { it.stencilType == StencilLayerType.MIDTONE } -> 
                    StencilLayerType.MIDTONE to StencilLayerCount.THREE
                else -> {
                    withContext(dispatchers.main) {
                        _uiState.update { it.copy(isLoading = false) }
                        Toast.makeText(context, "Maximum stencil layers (3) already generated for this group.", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
            }

            // 3. Process
            val isolated = subjectIsolator.isolate(composite).getOrNull()?.isolatedBitmap ?: composite
            
            stencilProcessor.processSingle(isolated, nextType, totalCount).collect { progress ->
                when (progress) {
                    is StencilProgress.Done -> {
                        val stencilLayer = progress.layers.first()
                        val filename = "stencil_${nextType.name.lowercase()}_${UUID.randomUUID()}.png"
                        val path = projectRepository.saveArtifact(projectId, filename, ImageUtils.bitmapToByteArray(stencilLayer.bitmap))
                        val localUri = "file://$path".toUri()

                        val newLayer = Layer(
                            id = UUID.randomUUID().toString(),
                            name = "Stencil${nextType.order} ${nextType.label}",
                            uri = localUri,
                            bitmap = stencilLayer.bitmap,
                            isLinked = true,
                            stencilType = nextType,
                            stencilSourceId = layerId
                        )

                        withContext(dispatchers.main) {
                            baseBitmaps[newLayer.id] = stencilLayer.bitmap.copy(Bitmap.Config.ARGB_8888, false)
                            layerStrokes[newLayer.id] = mutableListOf()
                            
                            _uiState.update { s ->
                                val idx = s.layers.indexOfFirst { it.id == layerId }
                                val updatedLayers = s.layers.toMutableList().also { list ->
                                    // Insert at top of linked group
                                    var topIdx = idx
                                    while (topIdx + 1 < list.size && list[topIdx + 1].isLinked) topIdx++
                                    list.add(topIdx + 1, newLayer)
                                }
                                s.copy(layers = updatedLayers, activeLayerId = newLayer.id, isLoading = false)
                            }
                            saveProject()
                        }
                    }
                    is StencilProgress.Error -> {
                        withContext(dispatchers.main) {
                            _uiState.update { it.copy(isLoading = false) }
                            Toast.makeText(context, "Stencil error: ${progress.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    override fun onGeneratePoster(layerId: String) {
        // This is called from the PosterOptionsDialog
    }

    fun generatePosterPdf(selectedLayerIds: List<String>, outputSizeMm: Float) {
        val state = _uiState.value
        val stencilLayers = state.layers.filter { it.id in selectedLayerIds }
            .mapNotNull { layer ->
                layer.stencilType?.let { type ->
                    layer.bitmap?.let { bmp ->
                        StencilLayer(type, bmp, layer.name)
                    }
                }
            }

        if (stencilLayers.isEmpty()) return

        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch(dispatchers.io) {
            val result = stencilPrintEngine.generatePdf(
                context,
                stencilLayers,
                outputSizeMm,
                StencilOutputDimension.WIDTH // Default to width for now
            )
            
            withContext(dispatchers.main) {
                _uiState.update { it.copy(isLoading = false) }
                result.fold(
                    onSuccess = { uri ->
                        // Share intent triggered via Activity/UI state or broadcast
                        // For simplicity, let's just toast or use a callback
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "application/pdf"
                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(android.content.Intent.createChooser(intent, "Share Stencil PDF").apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    },
                    onFailure = { e ->
                        Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
    }
}
