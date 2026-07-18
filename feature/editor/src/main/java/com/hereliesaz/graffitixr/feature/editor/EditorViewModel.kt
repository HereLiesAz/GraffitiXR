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
import com.hereliesaz.graffitixr.common.coop.OpEmitter
import com.hereliesaz.graffitixr.common.model.*
import com.hereliesaz.graffitixr.common.util.ImageUtils
import com.hereliesaz.graffitixr.common.util.computeAutoTune
import com.hereliesaz.graffitixr.common.util.decodeBoundedBitmap
import com.hereliesaz.graffitixr.common.azphalt.applyCubeLut
import com.hereliesaz.graffitixr.common.util.imageStats
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    private val dispatchers: DispatcherProvider,
    private val opEmitter: OpEmitter,
    private val extensionRepository: com.hereliesaz.graffitixr.data.azphalt.ExtensionRepository,
) : ViewModel(), EditorActions {

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState = _uiState.asStateFlow()

    /**
     * Per-host AzNavRail expansion state (host id -> expanded), surfaced from the current project so the
     * rail can restore exactly as the user left it on reopen. Empty until [onRailHostExpansionChanged]
     * populates it (which happens once AzNavRail exposes a per-host onExpandedChange — expected 10.11).
     */
    val railExpansion: StateFlow<Map<String, Boolean>> =
        projectRepository.currentProject
            .map { it?.railExpansion ?: emptyMap() }
            // Seed synchronously from the loaded project: initiallyExpanded is one-shot, so if the first
            // composition saw an empty map the restored state would be ignored when it arrived a frame later.
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                projectRepository.currentProject.value?.railExpansion ?: emptyMap()
            )

    /**
     * Persist a host item's expanded/collapsed state into the project record so it survives reopen.
     * Wired to AzNavRail's per-host `onExpandedChange` (10.11), which fires on manual toggles only.
     */
    fun onRailHostExpansionChanged(hostId: String, expanded: Boolean) {
        viewModelScope.launch(dispatchers.io) {
            projectRepository.updateProject { it.copy(railExpansion = it.railExpansion + (hostId to expanded)) }
        }
    }

    private val history = EditHistory()

    // Per-layer base-bitmap and stroke caches (thread-safe; see LayerStore).
    private val layerStore = LayerStore()

    // Stroke-compositing pipeline (base + strokes -> rendered bitmap; see DrawingEngine).
    private val drawingEngine = DrawingEngine(slamManager)
    // Debounced disk saves, keyed by layer id. A single shared job would let a save
    // scheduled for layer B cancel a still-pending save for layer A, silently dropping
    // A's strokes; per-layer jobs cancel only the same layer's superseded save.
    private val pendingSaveJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    // Per-layer rebuild jobs: cancels stale compositing coroutines on rapid undo/redo so
    // only the most recent rebuild's result lands in the UI state.
    private val rebuildJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    // Debounced project-preview thumbnail generation. saveProject() fires on nearly every edit,
    // so the thumbnail is regenerated at most once the edits settle, off the main thread.
    private var thumbnailJob: kotlinx.coroutines.Job? = null

    private var copiedLayerState: Layer? = null
    private var anchorHalfExtentMeters: Pair<Float, Float>? = null

    private var rawSegmentationConfidence: FloatArray? = null
    private var segmentationSourceBitmap: Bitmap? = null
    private var segmentationTargetLayerId: String? = null
    // Stencil-mode segmentation: pending pipeline info set while the slider is visible
    private var pendingStencilSourceLayerId: String? = null
    private var pendingStencilProjectId: String? = null

    // Real-time stroke state — valid only between onStrokeStart and onStrokeEnd.
    private var strokeWorkingBitmap: Bitmap? = null
    private var strokeWorkingCanvas: Canvas? = null
    private var strokePaint: Paint? = null
    private var strokePrevBitmapPoint: Offset? = null
    private val strokeCollectedPointsLock = Any()
    // Touched from the main thread (add/reset) and background Default coroutines (snapshot). All access
    // MUST go through the synchronized helpers below, or a concurrent add during toList() throws a
    // ConcurrentModificationException mid-stroke (uncaught in viewModelScope → crash).
    private var strokeCollectedPoints: MutableList<Offset> = mutableListOf()

    private fun resetStrokePoints(vararg initial: Offset) = synchronized(strokeCollectedPointsLock) {
        strokeCollectedPoints = initial.toMutableList()
    }

    private fun addStrokePoint(point: Offset) = synchronized(strokeCollectedPointsLock) {
        strokeCollectedPoints.add(point)
    }

    private fun snapshotStrokePoints(): List<Offset> = synchronized(strokeCollectedPointsLock) {
        strokeCollectedPoints.toList()
    }
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
                dispatch(EditorIntent.SetCanvasBackground(Color(argb.toLong() and 0xFFFFFFFFL)))
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

                        dispatch(EditorIntent.LoadedProject(project.id, layers))

                        val loadedModeAdjustments = project.modeAdjustments.mapNotNull { (key, value) ->
                            runCatching { EditorMode.valueOf(key) }.getOrNull()?.let { it to value }
                        }.toMap()
                        dispatch(EditorIntent.SetAllModeAdjustments(loadedModeAdjustments))

                        val layersToLoad = layers.filter { it.bitmap == null && it.uri != null }
                        if (layersToLoad.isNotEmpty()) {
                            viewModelScope.launch(dispatchers.io) {
                                val loadedLayers = layers.map { layer ->
                                    val layerUri = layer.uri
                                    if (layer.bitmap == null && layerUri != null) {
                                        val loadedBmp = ImageUtils.loadBitmapAsync(context, layerUri)
                                        if (loadedBmp != null) {
                                            layerStore.putBase(layer.id, loadedBmp.copy(Bitmap.Config.ARGB_8888, false))
                                            layerStore.initStrokes(layer.id)
                                        }
                                        layer.copy(bitmap = loadedBmp)
                                    } else {
                                        layer
                                    }
                                }
                                withContext(dispatchers.main) {
                                    dispatch(EditorIntent.SetLayers(loadedLayers))
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
                                val intr = project.fingerprintIntrinsics
                                val anchor = project.fingerprintAnchor
                                if (intr.size >= 4 && anchor.size == 16) {
                                    // Metric fingerprint: replay the true capture intrinsics + anchor
                                    // so reload reloc matches the live capture, not a default guess.
                                    slamManager.restoreWallFingerprintMetric(
                                        fp.descriptorsData,
                                        fp.descriptorsRows,
                                        fp.descriptorsCols,
                                        fp.descriptorsType,
                                        fp.points3d.toFloatArray(),
                                        anchor.toFloatArray(),
                                        intr.toFloatArray(),
                                    )
                                } else {
                                    slamManager.restoreWallFingerprint(
                                        fp.descriptorsData,
                                        fp.descriptorsRows,
                                        fp.descriptorsCols,
                                        fp.descriptorsType,
                                        fp.points3d.toFloatArray()
                                    )
                                }
                                // Restore the distortion-head canonical patch (256x256 raw gray).
                                if (fp.patchData.isNotEmpty()) {
                                    val s = kotlin.math.sqrt(fp.patchData.size.toDouble()).toInt()
                                    if (s * s == fp.patchData.size) slamManager.setWallPatchBytes(fp.patchData, s)
                                }
                            }
                        }

                        project.backgroundImageUri?.let { uri ->
                            viewModelScope.launch(dispatchers.io) {
                                val bitmap = ImageUtils.loadBitmapAsync(context, uri)
                                withContext(dispatchers.main) {
                                    dispatch(EditorIntent.SetBackgroundBitmap(bitmap))
                                }
                            }
                        }
                    }
                } else {
                    dispatch(EditorIntent.ClearProject)
                    slamManager.clearMap()
                    layerStore.clear()
                    history.clear()
                }
            }
        }
    }

    fun setEditorMode(mode: EditorMode) {
        // A mode switch is a view change, not a container change — but any interaction in flight
        // (a stroke not yet lifted, a segmentation not yet confirmed) must not survive it, or its
        // callbacks would commit to the previous view's layer. Mirror the reducer's no-op guard.
        if (_uiState.value.editorMode != mode) {
            clearTransientStrokeState()
            pendingStencilSourceLayerId = null
            pendingStencilProjectId = null
        }
        dispatch(EditorIntent.SetEditorMode(mode))
    }

    private fun pushHistory() {
        history.pushProperty(_uiState.value.layers.map { it.copy(bitmap = null) })
        updateHistoryCounts()
    }

    private fun updateHistoryCounts() {
        _uiState.update { it.copy(undoCount = history.undoCount, redoCount = history.redoCount) }
    }

    /** The current layer set, stripped of bitmaps — what we record so an undo can be reverted. */
    private fun currentLayerSnapshot(): List<Layer> = _uiState.value.layers.map { it.copy(bitmap = null) }

    override fun onUndoClicked() {
        val command = history.popUndo { undone ->
            when (undone) {
                is EditCommand.Draw -> undone
                is EditCommand.PropertyChange -> EditCommand.PropertyChange(currentLayerSnapshot())
            }
        } ?: return

        when (command) {
            is EditCommand.Draw -> {
                if (!layerStore.removeLastStroke(command.layerId)) return
                rebuildLayerBitmap(command.layerId, emitOp = true)
            }
            is EditCommand.PropertyChange -> {
                val currentBitmaps = _uiState.value.layers.associate { it.id to it.bitmap }
                val restoredLayers = command.oldLayers.map { it.copy(bitmap = currentBitmaps[it.id]) }
                dispatch(EditorIntent.SetLayers(restoredLayers))
                saveProject()
                emitLayerStateResync(restoredLayers)
            }
        }
        updateHistoryCounts()
    }

    override fun onRedoClicked() {
        val command = history.popRedo { redone ->
            when (redone) {
                is EditCommand.Draw -> redone
                is EditCommand.PropertyChange -> EditCommand.PropertyChange(currentLayerSnapshot())
            }
        } ?: return

        when (command) {
            is EditCommand.Draw -> {
                layerStore.addStroke(command.layerId, command.command)
                rebuildLayerBitmap(command.layerId, emitOp = true)
            }
            is EditCommand.PropertyChange -> {
                val currentBitmaps = _uiState.value.layers.associate { it.id to it.bitmap }
                val restoredLayers = command.oldLayers.map { it.copy(bitmap = currentBitmaps[it.id]) }
                dispatch(EditorIntent.SetLayers(restoredLayers))
                saveProject()
                emitLayerStateResync(restoredLayers)
            }
        }
        updateHistoryCounts()
    }

    private fun rebuildLayerBitmap(layerId: String, emitOp: Boolean = false) {
        val base = layerStore.base(layerId) ?: return
        val strokes = layerStore.strokes(layerId)

        rebuildJobs[layerId]?.cancel()
        rebuildJobs[layerId] = viewModelScope.launch(dispatchers.default) {
            // Compositing replays strokes through OpenCV (and SLAM for Liquify). Guard it so a
            // failure during undo/redo logs instead of taking down the app — the stroke list and
            // base are unchanged, so the next edit re-renders cleanly.
            try {
                val currentBitmap = drawingEngine.composite(base, strokes)

                if (emitOp) {
                    // Used by undo/redo: the layer's pixels changed in a way the guest can't replay,
                    // so push the whole baked bitmap.
                    opEmitter.emit(Op.LayerBitmapReplace(layerId, ImageUtils.bitmapToByteArray(currentBitmap)))
                }

                withContext(dispatchers.main) {
                    _uiState.update { state ->
                        state.copy(layers = state.layers.map { if (it.id == layerId) it.copy(bitmap = currentBitmap) else it })
                    }
                    val layer = _uiState.value.layers.find { it.id == layerId } ?: return@withContext
                    scheduleDiskSave(layerId, currentBitmap, layer.uri)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.e("EditorViewModel", "Failed to rebuild layer $layerId on undo/redo", e)
            }
        }
    }

    fun processNewStroke(layerId: String, activeBitmap: Bitmap, command: StrokeCommand, layer: Layer) {
        layerStore.addStroke(layerId, command)

        history.pushDraw(layerId, command)
        updateHistoryCounts()

        viewModelScope.launch(dispatchers.default) {
            val newBitmap = drawingEngine.applySingleStroke(activeBitmap, command)

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
        // Cancel only this layer's previous pending save, never another layer's.
        pendingSaveJobs.remove(layerId)?.cancel()
        val job = viewModelScope.launch(dispatchers.io) {
            kotlinx.coroutines.delay(1500)
            try {
                val file = java.io.File(path)
                java.io.FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Normal: a newer stroke superseded this debounced save. Not a failure.
                throw e
            } catch (e: Exception) {
                // A swallowed failure here means the user's edits are silently lost.
                android.util.Log.e("EditorViewModel", "Failed to save layer bitmap to $path", e)
                withContext(dispatchers.main) {
                    Toast.makeText(context, "Couldn't save your changes — storage may be full", Toast.LENGTH_LONG).show()
                }
            } finally {
                // Don't leak completed jobs in the map.
                pendingSaveJobs.remove(layerId, coroutineContext[kotlinx.coroutines.Job])
            }
        }
        pendingSaveJobs[layerId] = job
    }

    override fun onAddLayer(uri: Uri) {
        pushHistory()
        viewModelScope.launch(dispatchers.io) {
            // Cap imported layers at a screen-reasonable size. A full 12MP+ photo is ~48MB as ARGB;
            // decoding/copying/PNG-encoding it (then rendering it as a texture every frame) is what
            // made the first layer take seconds to appear and the canvas lag. 2048px is ample here.
            val bitmap = ImageUtils.loadBitmapAsync(context, uri, maxDimension = 2048)
            val projectId = _uiState.value.projectId
            if (bitmap != null && projectId != null) {
                val filename = "layer_${UUID.randomUUID()}.png"
                val path = projectRepository.saveArtifact(projectId, filename, ImageUtils.bitmapToByteArray(bitmap))
                val localUri = "file://$path".toUri()

                val metrics = context.resources.displayMetrics
                val screenW = metrics.widthPixels.toFloat()
                val screenH = metrics.heightPixels.toFloat()
                
                // Calculate initial scale to fit the screen
                val scaleW = screenW * 0.9f / bitmap.width
                val scaleH = screenH * 0.9f / bitmap.height
                val initialScale = minOf(scaleW, scaleH, 1.0f)

                val newLayer = Layer(
                    id = UUID.randomUUID().toString(),
                    name = "Layer ${_uiState.value.layers.size + 1}",
                    uri = localUri,
                    bitmap = bitmap,
                    isVisible = true,
                    scale = initialScale
                )

                layerStore.putBase(newLayer.id, bitmap.copy(Bitmap.Config.ARGB_8888, false))
                layerStore.initStrokes(newLayer.id)

                withContext(dispatchers.main) {
                    dispatch(EditorIntent.AddLayer(newLayer))
                    opEmitter.emit(Op.LayerAdd(newLayer))
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

                layerStore.putBase(newLayer.id, blankBitmap.copy(Bitmap.Config.ARGB_8888, false))
                layerStore.initStrokes(newLayer.id)

                dispatch(EditorIntent.AddLayer(newLayer))
                opEmitter.emit(Op.LayerAdd(newLayer))
                saveProject()
            }
        }
    }

    fun setBackgroundImage(uri: Uri) {
        val projectId = _uiState.value.projectId ?: return
        viewModelScope.launch(dispatchers.io) {
            dispatch(EditorIntent.SetLoading(true))
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
                    dispatch(EditorIntent.SetBackgroundBitmap(bitmap)); dispatch(EditorIntent.SetLoading(false))
                }
            } else {
                withContext(dispatchers.main) {
                    dispatch(EditorIntent.SetLoading(false))
                }
            }
        }
    }

    /** Remove the Mockup wall photo: clears the persisted background URI and the live bitmap. */
    fun clearBackgroundImage() {
        viewModelScope.launch(dispatchers.io) {
            projectRepository.currentProject.value?.let { project ->
                projectRepository.updateProject(project.copy(backgroundImageUri = null))
            }
            withContext(dispatchers.main) {
                dispatch(EditorIntent.SetBackgroundBitmap(null))
            }
        }
    }

    fun saveProject(name: String? = null) {
        viewModelScope.launch(dispatchers.io) {
            try {
            val currentProject = projectRepository.currentProject.value
            val updatedLayers = _uiState.value.layers.map { it.toOverlayLayer() }
            val modeAdjustments = _uiState.value.modeAdjustments.mapKeys { it.key.name }

            val projectToSave = if (currentProject == null) {
                GraffitiProject(name = name ?: "New Project", layers = updatedLayers, modeAdjustments = modeAdjustments)
            } else {
                currentProject.copy(
                    name = name ?: currentProject.name,
                    layers = updatedLayers,
                    modeAdjustments = modeAdjustments,
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

            scheduleThumbnailUpdate()
            } catch (e: Exception) {
                // Don't let a failed save die silently — the user believes their work is safe.
                android.util.Log.e("EditorViewModel", "Failed to save project", e)
                withContext(dispatchers.main) {
                    Toast.makeText(context, "Couldn't save the project — storage may be full", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Regenerates the project's preview thumbnail off the main thread, debounced so the rapid
     * stream of autosaves doesn't composite on every stroke. Writes the standard
     * projects/<id>/thumbnail.png (which ProjectManager.saveProject also auto-detects) and records
     * its uri on the project. The update keeps the same project id, so the currentProject collector
     * treats it as a no-op and never reloads the editor.
     */
    private fun scheduleThumbnailUpdate() {
        val projectId = _uiState.value.projectId ?: return
        // Confine the job cancel/assign to the main thread so concurrent saveProject() calls (which
        // run on the multi-threaded IO dispatcher) can't race on thumbnailJob and leak coroutines.
        viewModelScope.launch(dispatchers.main) {
            thumbnailJob?.cancel()
            thumbnailJob = viewModelScope.launch(dispatchers.default) {
                try {
                    kotlinx.coroutines.delay(2000)
                    if (_uiState.value.layers.none { it.isVisible && it.bitmap != null }) return@launch
                    val metrics = context.resources.displayMetrics
                    val w = metrics.widthPixels.takeIf { it > 0 } ?: 1080
                    val h = metrics.heightPixels.takeIf { it > 0 } ?: 1920
                    val composite = exportManager.compositeLayers(_uiState.value.layers, w, h)
                    // Downscale to a small preview so the file stays tiny and decodes fast.
                    val maxDim = 512
                    val longest = maxOf(composite.width, composite.height).coerceAtLeast(1)
                    val scale = maxDim.toFloat() / longest
                    val thumb = if (scale < 1f) {
                        Bitmap.createScaledBitmap(
                            composite,
                            (composite.width * scale).toInt().coerceAtLeast(1),
                            (composite.height * scale).toInt().coerceAtLeast(1),
                            true
                        )
                    } else composite
                    val bytes = ImageUtils.bitmapToByteArray(thumb)
                    if (thumb !== composite) thumb.recycle()
                    composite.recycle()
                    val path = projectRepository.saveArtifact(projectId, "thumbnail.png", bytes)
                    projectRepository.updateProject {
                        if (it.id == projectId) it.copy(thumbnailUri = "file://$path".toUri()) else it
                    }
                } catch (e: Exception) {
                    // Thumbnails are best-effort; never let one crash the app.
                    android.util.Log.e("EditorViewModel", "Failed to generate thumbnail", e)
                }
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

    /**
     * Export the current design as a PNG saved to the gallery.
     *
     * @param backgroundBitmap When non-null, used as the export's background (Overlay: the CameraX
     *   still; AR: the composited GL framebuffer readback that already includes the wall-anchored
     *   overlay). When null, per-mode default applies: Mockup reads uiState.backgroundBitmap;
     *   Overlay/AR paths without a captured frame fall back to transparent (only reachable if
     *   the caller neglected to supply one); Trace/Design have no background.
     * @param skipLayerComposite When true, the [backgroundBitmap] IS the export — no layers are
     *   drawn on top. Set by the AR path because the GL readback already contains the layers as
     *   the wall-anchored quad; drawing them again would double-draw.
     */
    fun exportImage(backgroundBitmap: Bitmap? = null, skipLayerComposite: Boolean = false) {
        viewModelScope.launch(dispatchers.default) {
            dispatch(EditorIntent.SetLoading(true))
            try {
                val exportBitmap = if (skipLayerComposite && backgroundBitmap != null) {
                    // AR path: GL readback already contains camera + wall-anchored overlay.
                    // Save as-is; drawing the flat editor layers on top would double-draw them.
                    backgroundBitmap
                } else {
                    val metrics = context.resources.displayMetrics
                    val bgBmp = backgroundBitmap
                        ?: if (_uiState.value.editorMode == EditorMode.MOCKUP) _uiState.value.backgroundBitmap else null
                    // Trace previously baked canvasBackground colour into the export. Spec is
                    // "overlay layers only, no background", so use TRANSPARENT unconditionally —
                    // the PNG writer (saveBitmapToGallery uses CompressFormat.PNG) preserves alpha.
                    exportManager.compositeLayers(
                        _uiState.value.layers,
                        metrics.widthPixels,
                        metrics.heightPixels,
                        backgroundBitmap = bgBmp,
                        backgroundColor = android.graphics.Color.TRANSPARENT,
                    )
                }

                val success = saveBitmapToGallery(context, exportBitmap)

                withContext(dispatchers.main) {
                    dispatch(EditorIntent.SetLoading(false))
                    if (success) {
                        Toast.makeText(context, "Image saved to gallery", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "Failed to save image", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(dispatchers.main) {
                    dispatch(EditorIntent.SetLoading(false))
                    Toast.makeText(context, "Export error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun toggleHandedness() = dispatch(EditorIntent.ToggleHandedness)
    fun toggleDiagOverlay() = dispatch(EditorIntent.ToggleDiagOverlay)
    fun toggleFeaturePoints() = dispatch(EditorIntent.ToggleFeaturePoints)
    fun togglePlaneGrids() = dispatch(EditorIntent.TogglePlaneGrids)
    fun toggleVoxels() = dispatch(EditorIntent.ToggleVoxels)
    fun togglePoints() = dispatch(EditorIntent.TogglePoints)
    fun toggleMesh() = dispatch(EditorIntent.ToggleMesh)
    fun applyMethodLayerDefaults(method: com.hereliesaz.graffitixr.common.model.MuralMethod) =
        dispatch(EditorIntent.ApplyMethodLayerDefaults(method))
    fun setActiveTool(tool: Tool) = dispatch(EditorIntent.SetActiveTool(tool))

    override fun onLayerActivated(id: String) = dispatch(EditorIntent.ActivateLayer(id))

    override fun onLayerRemoved(id: String) {
        pushHistory()
        dispatch(EditorIntent.RemoveLayer(id))
        layerStore.remove(id)
        opEmitter.emit(Op.LayerRemove(id))
        saveProject()
    }

    override fun onLayerReordered(newOrder: List<String>) {
        pushHistory()
        dispatch(EditorIntent.ReorderLayers(newOrder))
        opEmitter.emit(Op.LayerReorder(newOrder))
        saveProject()
    }

    override fun onRemoveBackgroundClicked() {
        val state = _uiState.value
        val layerId = state.activeLayerId ?: return
        val layer = state.layers.find { it.id == layerId } ?: return
        val projectId = state.projectId ?: return
        val uri = layer.uri ?: return

        pushHistory()
        dispatch(EditorIntent.SetLoading(true))

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
                        dispatch(EditorIntent.BeginSegmentation)
                    }
                }
            }
            withContext(dispatchers.main) {
                dispatch(EditorIntent.SetLoading(false))
            }
        }
    }

    fun setSegmentationInfluence(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        dispatch(EditorIntent.SetSegmentationInfluence(clamped))

        val confidence = rawSegmentationConfidence ?: return
        val source = segmentationSourceBitmap ?: return
        val targetId = segmentationTargetLayerId

        viewModelScope.launch(dispatchers.default) {
            val newBitmap = subjectIsolator.applyConfidenceThreshold(source, confidence, clamped, 0.1f)

            val finalPreview = if (pendingStencilSourceLayerId != null) {
                val polarity = stencilProcessor.assessTonalPolarity(newBitmap)
                val mask = stencilProcessor.alphaToMask(newBitmap)
                val stencilLayers = stencilProcessor.kmeansLayers(newBitmap, mask, polarity, clamped)

                val combined = Bitmap.createBitmap(newBitmap.width, newBitmap.height, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(combined)
                stencilLayers.forEach { stencilLayer ->
                    canvas.drawBitmap(stencilLayer.bitmap, 0f, 0f, null)
                }
                combined
            } else {
                newBitmap
            }

            withContext(dispatchers.main) {
                if (targetId != null) {
                    _uiState.update { state ->
                        state.copy(
                            layers = state.layers.map { layer ->
                                if (layer.id == targetId) layer.copy(bitmap = finalPreview) else layer
                            }
                        )
                    }
                } else {
                    // Update live preview for stencil generation
                    dispatch(EditorIntent.SetSegmentationPreview(finalPreview))
                }
            }
        }
    }

    override fun onConfirmSegmentation() {
        val stencilSourceLayerId = pendingStencilSourceLayerId
        val stencilProjectId = pendingStencilProjectId
        val confidence = rawSegmentationConfidence
        val source = segmentationSourceBitmap
        val influence = _uiState.value.segmentationInfluence
        val targetLayerId = segmentationTargetLayerId

        rawSegmentationConfidence = null
        segmentationSourceBitmap = null
        segmentationTargetLayerId = null
        pendingStencilSourceLayerId = null
        pendingStencilProjectId = null
        dispatch(EditorIntent.EndSegmentation)

        if (stencilSourceLayerId != null && stencilProjectId != null) {
            dispatch(EditorIntent.SetLoading(true))
            viewModelScope.launch(dispatchers.default) {
                val isolated = if (confidence != null && source != null)
                    subjectIsolator.applyConfidenceThreshold(source, confidence, influence, 0.1f)
                else source ?: return@launch
                runStencilPipeline(isolated, stencilSourceLayerId, stencilProjectId, influence)
                withContext(dispatchers.main) {
                    dispatch(EditorIntent.SetLoading(false))
                }
            }
        } else if (targetLayerId != null && confidence != null && source != null) {
            // Background-removal path: setSegmentationInfluence only updated the in-memory
            // Layer.bitmap (which is @Transient and never serialized). Recompute the adjusted
            // isolation deterministically and overwrite the layer's file so the slider
            // adjustment survives a reload instead of reverting to the default threshold.
            dispatch(EditorIntent.SetLoading(true))
            viewModelScope.launch(dispatchers.default) {
                val adjusted = subjectIsolator.applyConfidenceThreshold(source, confidence, influence, 0.1f)
                val state = _uiState.value
                val projectId = state.projectId
                val filename = state.layers.find { it.id == targetLayerId }?.uri?.path?.substringAfterLast('/')
                if (projectId != null && filename != null) {
                    val path = projectRepository.saveArtifact(projectId, filename, ImageUtils.bitmapToByteArray(adjusted))
                    withContext(dispatchers.main) { updateLayerUri(targetLayerId, "file://$path".toUri()) }
                }
                withContext(dispatchers.main) { dispatch(EditorIntent.SetLoading(false)) }
            }
        }
    }

    override fun onCancelSegmentation() {
        rawSegmentationConfidence = null
        segmentationSourceBitmap = null
        segmentationTargetLayerId = null
        pendingStencilSourceLayerId = null
        pendingStencilProjectId = null
        dispatch(EditorIntent.EndSegmentation)
    }

    fun dismissSegmentationSlider() {
        onConfirmSegmentation()
    }

    override fun onSketchClicked() {
        val state = _uiState.value
        val layerId = state.activeLayerId ?: return
        val layer = state.layers.find { it.id == layerId } ?: return
        val projectId = state.projectId ?: return
        val uri = layer.uri ?: return

        pushHistory()
        dispatch(EditorIntent.SetLoading(true))

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
                        isLinked = false,
                        blendMode = androidx.compose.ui.graphics.BlendMode.SrcOver,
                        scale = layer.scale,
                        offset = layer.offset,
                        rotationX = layer.rotationX,
                        rotationY = layer.rotationY,
                        rotationZ = layer.rotationZ,
                        warpMesh = layer.warpMesh
                    )
                    withContext(dispatchers.main) {
                        _uiState.update { s ->
                            val idx = s.layers.indexOfFirst { it.id == layerId }
                            if (idx < 0) return@update s
                            val newLayers = s.layers.toMutableList().also { list ->
                                // Find top of the linked group to avoid splitting it
                                var topIdx = idx
                                while (topIdx + 1 < list.size && list[topIdx + 1].isLinked) topIdx++
                                // Insert sketch layer above the group
                                list.add(topIdx + 1, sketchLayer)
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
                dispatch(EditorIntent.SetLoading(false))
            }
        }
    }

    override fun onSketchThicknessChanged(thickness: Int) {
        dispatch(EditorIntent.SetSketchThickness(thickness))
    }

    override fun onApplyCannyEdgeClicked() {
        val state = _uiState.value
        val layerId = state.activeLayerId ?: return
        val layer = state.layers.find { it.id == layerId } ?: return
        val projectId = state.projectId ?: return
        val uri = layer.uri ?: return

        pushHistory()
        dispatch(EditorIntent.SetLoading(true))

        viewModelScope.launch(dispatchers.default) {
            val bitmap = ImageUtils.loadBitmapAsync(context, uri)
            if (bitmap != null) {
                val cannyBitmap = ImageProcessor.applyCannyEdgeDetection(bitmap, 50.0, 150.0)
                val path = projectRepository.saveArtifact(
                    projectId,
                    "canny_${System.currentTimeMillis()}.png",
                    ImageUtils.bitmapToByteArray(cannyBitmap)
                )
                val cannyUri = android.net.Uri.parse("file://$path")
                val cannyLayer = Layer(
                    id = java.util.UUID.randomUUID().toString(),
                    name = "Canny – ${layer.name}",
                    uri = cannyUri,
                    isSketch = true,
                    isLinked = false,
                    blendMode = androidx.compose.ui.graphics.BlendMode.SrcOver,
                    scale = layer.scale,
                    offset = layer.offset,
                    rotationX = layer.rotationX,
                    rotationY = layer.rotationY,
                    rotationZ = layer.rotationZ,
                    warpMesh = layer.warpMesh
                )
                withContext(dispatchers.main) {
                    _uiState.update { s ->
                        val idx = s.layers.indexOfFirst { it.id == layerId }
                        if (idx < 0) return@update s
                        val newLayers = s.layers.toMutableList().apply {
                            var topIdx = idx
                            while (topIdx + 1 < size && get(topIdx + 1).isLinked) topIdx++
                            add(topIdx + 1, cannyLayer)
                        }
                        s.copy(layers = newLayers, isLoading = false)
                    }
                }
                updateLayerUri(cannyLayer.id, cannyUri)
                return@launch
            }
            withContext(dispatchers.main) {
                dispatch(EditorIntent.SetLoading(false))
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
                                layerStore.putBase(id, bmp.copy(Bitmap.Config.ARGB_8888, false))
                                layerStore.initStrokes(id)
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
    override fun onAdjustClicked() = dispatch(EditorIntent.ToggleAdjustPanel)
    fun onBalanceClicked() = dispatch(EditorIntent.ToggleColorPanel)
    override fun onDismissPanel() = dispatch(EditorIntent.DismissPanel)

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

    // ── Per-mode whole-design adjustments ─────────────────────────────────────

    fun onModeTransformGesture(mode: EditorMode, pan: Offset, zoom: Float, rotationDelta: Float) {
        dispatch(EditorIntent.ApplyModeTransformGesture(mode, pan, zoom, rotationDelta))
    }

    /** Toggle the per-mode transform lock — pins/unpins the whole-design position for [mode]. */
    fun onToggleModeTransformLocked(mode: EditorMode) {
        dispatch(EditorIntent.ToggleModeTransformLocked(mode))
        saveProject()
    }

    override fun onGestureEnd() {
        saveProject()
        dispatch(EditorIntent.SetGestureInProgress(false))
        // Emit LayerTransform for the active layer. The editor stores transform as
        // scale/offset/rotationX/Y/Z rather than a Matrix, so we encode them in the
        // first 6 slots of a 16-float list (slots 6-15 are zeros).
        // applySpectatorOp must decode using the same convention.
        val state = _uiState.value
        val activeId = state.activeLayerId ?: return
        val layer = state.layers.find { it.id == activeId } ?: return
        val encodedMatrix = listOf(
            layer.scale, layer.offset.x, layer.offset.y,
            layer.rotationX, layer.rotationY, layer.rotationZ,
            0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f
        )
        opEmitter.emit(Op.LayerTransform(activeId, encodedMatrix))
    }
    override fun onGestureStart() { 
        pushHistory()
        dispatch(EditorIntent.BeginGesture) 
    }
    override fun toggleImageLock() {
        pushHistory()
        dispatch(EditorIntent.ToggleImageLock)
        saveProject()
        emitActiveLayerProps()
    }
    override fun onToggleInvert() {
        pushHistory()
        dispatch(EditorIntent.ToggleInvert)
        saveProject()
        emitActiveLayerProps()
    }
    /**
     * Opacity / brightness / contrast / saturation knobs. In Design they adjust the active layer; in
     * a Mode (AR/Overlay/Mockup/Trace) they adjust the whole-design [ModeAdjustment] for that mode,
     * which always exists — so the knob works even when no layer is selected and tones the entire
     * projected design (what the user expects in a Mode). Returns true when handled as a mode adjust.
     */
    private fun dispatchModeAdjustIfInMode(field: (ModeAdjustment) -> ModeAdjustment): Boolean {
        val st = _uiState.value
        if (st.editorMode == EditorMode.DESIGN) return false
        val cur = st.modeAdjustments[st.editorMode] ?: ModeAdjustment()
        dispatch(EditorIntent.SetModeAdjustment(st.editorMode, field(cur)))
        return true
    }
    override fun onOpacityChanged(v: Float) {
        if (!dispatchModeAdjustIfInMode { it.copy(opacity = v) }) dispatch(EditorIntent.SetOpacity(v))
    }
    override fun onBrightnessChanged(v: Float) {
        if (!dispatchModeAdjustIfInMode { it.copy(brightness = v) }) dispatch(EditorIntent.SetBrightness(v))
    }
    override fun onContrastChanged(v: Float) {
        if (!dispatchModeAdjustIfInMode { it.copy(contrast = v) }) dispatch(EditorIntent.SetContrast(v))
    }
    override fun onSaturationChanged(v: Float) {
        if (!dispatchModeAdjustIfInMode { it.copy(saturation = v) }) dispatch(EditorIntent.SetSaturation(v))
    }
    override fun onColorBalanceRChanged(v: Float) = dispatch(EditorIntent.SetColorBalanceR(v))
    override fun onColorBalanceGChanged(v: Float) = dispatch(EditorIntent.SetColorBalanceG(v))
    override fun onColorBalanceBChanged(v: Float) = dispatch(EditorIntent.SetColorBalanceB(v))

    /**
     * Apply an installed azphalt LUT extension to the active layer — the "use a marketplace plugin"
     * payoff. Grades the layer's current bitmap through the extension's `.cube` 3D LUT (a transform a
     * ColorMatrix can't express) and replaces the base, pushing undo history first.
     */
    fun applyInstalledLut(extensionId: String) {
        val layerId = _uiState.value.activeLayerId ?: return
        val layer = _uiState.value.layers.find { it.id == layerId } ?: return
        val bitmap = layer.bitmap ?: return
        pushHistory()
        dispatch(EditorIntent.SetLoading(true))
        viewModelScope.launch(dispatchers.default) {
            val lut = extensionRepository.loadLut(extensionId)
            if (lut == null) {
                dispatch(EditorIntent.SetLoading(false))
                return@launch
            }
            val graded = bitmap.applyCubeLut(lut)
            val base = graded.copy(Bitmap.Config.ARGB_8888, false)
            if (base != graded) graded.recycle()
            layerStore.putBase(layerId, base)
            layerStore.initStrokes(layerId)
            rebuildLayerBitmap(layerId, emitOp = true)
            dispatch(EditorIntent.SetLoading(false))
        }
    }

    /**
     * First-run doodle demo: on the scribble->artwork swap, pre-set the adjustment knobs to values
     * that read well against the wall. Combines [wall] (from the doodle capture) with the active
     * layer's own colour/contrast and applies through the existing setters (which route the
     * multiplicative/additive knobs to the AR mode-adjustment and colour balance to the layer, exactly
     * as the AR composite consumes them). A starting point the user then fine-tunes — not a hard grade.
     */
    fun autoTuneActiveLayer(wall: com.hereliesaz.graffitixr.common.util.ImageStats?) {
        if (wall == null) return
        val bitmap = _uiState.value.layers.find { it.id == _uiState.value.activeLayerId }?.bitmap ?: return
        viewModelScope.launch(dispatchers.default) {
            val art = bitmap.imageStats()
            val t = computeAutoTune(wall, art)
            withContext(dispatchers.main) {
                onOpacityChanged(t.opacity)
                onBrightnessChanged(t.brightness)
                onContrastChanged(t.contrast)
                onSaturationChanged(t.saturation)
                onColorBalanceRChanged(t.colorBalanceR)
                onColorBalanceGChanged(t.colorBalanceG)
                onColorBalanceBChanged(t.colorBalanceB)
            }
        }
    }
    override fun onScaleChanged(s: Float) = dispatch(EditorIntent.SetScale(s))
    override fun onOffsetChanged(o: Offset) = dispatch(EditorIntent.AddOffset(o))

    override fun onRotationXChanged(d: Float) = dispatch(EditorIntent.SetRotationX(d))
    override fun onRotationYChanged(d: Float) = dispatch(EditorIntent.SetRotationY(d))
    override fun onRotationZChanged(d: Float) = dispatch(EditorIntent.SetRotationZ(d))

    override fun onCycleRotationAxis() = dispatch(EditorIntent.CycleRotationAxis)

    override fun onAdjustmentStart() { pushHistory(); dispatch(EditorIntent.SetGestureInProgress(true)) }

    override fun onAdjustmentEnd() {
        dispatch(EditorIntent.SetGestureInProgress(false))
        saveProject()
        // Emit LayerPropsChange for the active layer after adjustment is committed.
        emitActiveLayerProps()
    }

    override fun setLayerTransform(scale: Float, offset: Offset, rx: Float, ry: Float, rz: Float) {
        dispatch(EditorIntent.SetLayerTransform(scale, offset, rx, ry, rz))
        saveProject()
    }

    override fun onLayerWarpChanged(layerId: String, mesh: List<Float>) {
        dispatch(EditorIntent.SetLayerWarp(layerId, mesh))
        saveProject()
    }

    override fun copyLayerModifications(id: String) { copiedLayerState = _uiState.value.layers.find { it.id == id } }

    override fun pasteLayerModifications(id: String) {
        val source = copiedLayerState ?: return
        pushHistory()
        dispatch(EditorIntent.PasteLayerModifications(id, source))
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
        _uiState.value.activeLayerId?.let { id ->
            _uiState.value.layers.find { it.id == id }?.let { opEmitter.emit(Op.LayerPropsChange(id, it.toLayerProps())) }
        }
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
                layerStore.putBase(duplicated.id, bmp.copy(Bitmap.Config.ARGB_8888, false))
                layerStore.initStrokes(duplicated.id)
            }

            withContext(dispatchers.main) {
                dispatch(EditorIntent.AddLayer(duplicated, resetActivePanel = false))
                saveProject()
            }
        }
    }

    override fun onLayerRenamed(id: String, name: String) {
        pushHistory()
        dispatch(EditorIntent.RenameLayer(id, name))
        saveProject()
    }

    /** Re-pushes layer order, props and transforms to guests after a non-draw undo/redo. */
    private fun emitLayerStateResync(layers: List<Layer>) {
        opEmitter.emit(Op.LayerReorder(layers.map { it.id }))
        layers.forEach { l ->
            opEmitter.emit(Op.LayerPropsChange(l.id, l.toLayerProps()))
            opEmitter.emit(Op.LayerTransform(l.id, listOf(
                l.scale, l.offset.x, l.offset.y, l.rotationX, l.rotationY, l.rotationZ,
                0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f
            )))
        }
    }

    private fun Layer.toLayerProps() = LayerProps(
        isVisible = isVisible,
        opacity = opacity,
        brightness = brightness,
        contrast = contrast,
        saturation = saturation,
        colorBalanceR = colorBalanceR,
        colorBalanceG = colorBalanceG,
        colorBalanceB = colorBalanceB,
        isImageLocked = isImageLocked,
        isInverted = isInverted,
        blendMode = blendMode
    )

    private fun updateActiveLayer(transform: (Layer) -> Layer) {
        _uiState.update { state ->
            val id = state.activeLayerId ?: return@update state
            state.copy(layers = LayerListOps.mapLayer(state.layers, id, transform))
        }
    }

    fun updateAllLayers(transform: (Layer) -> Layer) {
        _uiState.update { state ->
            state.copy(layers = state.layers.map(transform))
        }
    }

    /**
     * MVI dispatch: apply a state-only [EditorIntent] through the pure [EditorReducer]. Side
     * effects (history, persistence, co-op op emission) are orchestrated by the caller around
     * this call — the reducer itself stays pure.
     */
    private fun dispatch(intent: EditorIntent) {
        _uiState.update { EditorReducer.reduce(it, intent) }
    }

    /** Emits a co-op LayerPropsChange for the active layer, if any. */
    private fun emitActiveLayerProps() {
        val id = _uiState.value.activeLayerId ?: return
        _uiState.value.layers.find { it.id == id }?.let { opEmitter.emit(Op.LayerPropsChange(id, it.toLayerProps())) }
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

    override fun onFeedbackShown() = dispatch(EditorIntent.FeedbackShown)
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

        resetStrokePoints(startPoint)
        strokeLayerId = layerId
        strokeCanvasW = canvasSize.width
        strokeCanvasH = canvasSize.height
        strokeLayerScale = layer.scale
        strokeLayerOffset = layer.offset
        strokeLayerRotationZ = layer.rotationZ

        if (state.activeTool == Tool.LIQUIFY) {
            // Store the original bitmap so live-preview warps can be applied from a clean copy.
            liquifyOriginalBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, false)
            slamManager.prepareLiquify(originalBitmap)
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
            // Read the transform off the captured immutable `layer` (not the mutable stroke* members,
            // which a quick second stroke could overwrite before this coroutine runs).
            val layerScale = layer.scale
            val layerOffset = layer.offset
            val layerRotationZ = layer.rotationZ
            // Match the rail size preview exactly: brushSize is screen px; scale it into this layer's
            // bitmap space (1f for an unscaled sketch) so the painted dab is the previewed diameter.
            val brushScale = ImageProcessor.screenToBitmapScale(
                canvasSize.width, canvasSize.height, workBitmap.width, workBitmap.height, layerScale
            )
            val paint = buildStrokePaint(tool, argb, brushSize * brushScale, feathering)

            // Snapshot the collected points at this moment — may include points that arrived
            // during the bitmap-copy phase.
            val catchUpPoints = snapshotStrokePoints()
            val mappedAll = ImageProcessor.mapScreenToBitmap(
                catchUpPoints, canvasSize.width, canvasSize.height, workBitmap.width, workBitmap.height,
                layerScale, layerOffset, layerRotationZ
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
        addStrokePoint(currentPoint)

        // Liquify live preview: cancel any pending warp job and start a fresh one from the
        // original bitmap so each drag frame shows the full accumulated warp.
        if (_uiState.value.activeTool == Tool.LIQUIFY) {
            val layerId = strokeLayerId ?: return
            // Capture the original bitmap once: onStrokeEnd may null the field while the warp job
            // below is still queued on the default dispatcher, and a fast tool switch can enter this
            // branch before onStrokeStart populated it. Bailing here is preferable to an NPE.
            val original = liquifyOriginalBitmap ?: return
            val points = snapshotStrokePoints()
            val canvasW = strokeCanvasW
            val canvasH = strokeCanvasH
            val brushSize = _uiState.value.brushSize
            val capturedScale = strokeLayerScale
            val capturedOffset = strokeLayerOffset
            val capturedRotZ = strokeLayerRotationZ

            // Apply incremental liquify to the native engine
            if (points.size >= 2) {
                val p1 = points[points.size - 2]
                val p2 = points.last()

                // We need to map these screen points to the bitmap space
                val mapped = ImageProcessor.mapScreenToBitmap(
                    listOf(p1, p2), canvasW, canvasH, original.width, original.height,
                    capturedScale, capturedOffset, capturedRotZ
                )

                val strokeArr = floatArrayOf(mapped[0].x, mapped[0].y, mapped[1].x, mapped[1].y)
                slamManager.applyLiquify(strokeArr, brushSize, 0.5f)
            }

            liquifyJob?.cancel()
            liquifyJob = viewModelScope.launch(dispatchers.default) {
                val warpBitmap = original.copy(Bitmap.Config.ARGB_8888, true)
                slamManager.bakeLiquify(warpBitmap)

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
        val points = snapshotStrokePoints()
        val canvasW = strokeCanvasW
        val canvasH = strokeCanvasH

        val capturedScale = strokeLayerScale
        val capturedOffset = strokeLayerOffset
        val capturedRotationZ = strokeLayerRotationZ

        if (state.activeTool == Tool.LIQUIFY || strokeWorkingBitmap == null) {
            // Liquify (or a stroke so fast the background copy hadn't finished):
            // fall back to the full whole-stroke approach.
            val bitmap = layer.bitmap ?: return
            
            val finalBitmap = if (state.activeTool == Tool.LIQUIFY) {
                // Fall back to the committed layer bitmap if the original was already cleared
                // (e.g. a second onStrokeEnd, or a start that never populated it) rather than NPE.
                val baked = (liquifyOriginalBitmap ?: bitmap).copy(Bitmap.Config.ARGB_8888, true)
                slamManager.bakeLiquify(baked)
                baked
            } else {
                // Fast stroke: the background working-bitmap copy never finished before finger-up, so
                // rasterize the whole stroke onto a fresh copy here. Committing `bitmap` unchanged (the
                // old behaviour) silently dropped the stroke — it lived only in history, which isn't
                // replayed on reload, so it vanished on screen and on disk.
                val target = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                if (points.isNotEmpty()) {
                    val canvas = android.graphics.Canvas(target)
                    val brushScale = ImageProcessor.screenToBitmapScale(
                        canvasW, canvasH, target.width, target.height, capturedScale
                    )
                    val paint = buildStrokePaint(
                        state.activeTool, state.activeColor.toArgb(), state.brushSize * brushScale, state.brushFeathering
                    )
                    val mapped = ImageProcessor.mapScreenToBitmap(
                        points, canvasW, canvasH, target.width, target.height,
                        capturedScale, capturedOffset, capturedRotationZ
                    )
                    if (mapped.size == 1) {
                        canvas.drawPoint(mapped[0].x, mapped[0].y, paint)
                    } else if (mapped.size > 1) {
                        val seg = android.graphics.Path()
                        seg.moveTo(mapped[0].x, mapped[0].y)
                        for (i in 1 until mapped.size) seg.lineTo(mapped[i].x, mapped[i].y)
                        canvas.drawPath(seg, paint)
                    }
                }
                target
            }

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

            // Add stroke to history
            layerStore.addStroke(layerId, command)
            history.pushDraw(layerId, command)
            updateHistoryCounts()

            _uiState.update { s ->
                s.copy(
                    layers = s.layers.map { if (it.id == layerId) it.copy(bitmap = finalBitmap) else it },
                    liveStrokeLayerId = null,
                    liveStrokeBitmap = null
                )
            }
            scheduleDiskSave(layerId, finalBitmap, layer.uri)
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
            layerStore.addStroke(layerId, command)
            history.pushDraw(layerId, command)
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

        // Co-op sync: replayable brush strokes go as StrokeComplete; Liquify bakes into the
        // bitmap and can't map to a BrushStroke, so it propagates as a whole-bitmap replace.
        if (state.activeTool != Tool.LIQUIFY) {
            val bitmap = layer.bitmap
            if (bitmap != null) {
                val mappedPoints = ImageProcessor.mapScreenToBitmap(
                    points, canvasW, canvasH, bitmap.width, bitmap.height,
                    capturedScale, capturedOffset, capturedRotationZ
                )
                val pointsFlat = mappedPoints.flatMap { listOf(it.x, it.y) }
                val brushStroke = BrushStroke(
                    points = pointsFlat,
                    colorArgb = state.activeColor.toArgb().toLong() and 0xFFFFFFFFL,
                    brushSize = state.brushSize,
                    brushFeathering = state.brushFeathering,
                    blendModeOrdinal = state.activeTool.ordinal
                )
                opEmitter.emit(Op.StrokeComplete(layerId, brushStroke))
            }
        } else {
            val baked = _uiState.value.layers.find { it.id == layerId }?.bitmap
            if (baked != null) {
                opEmitter.emit(Op.LayerBitmapReplace(layerId, ImageUtils.bitmapToByteArray(baked)))
            }
        }

        clearTransientStrokeState()
    }

    /**
     * Resets the imperative, in-flight stroke/liquify scratch state. These live as ViewModel fields
     * (not in [EditorUiState]), so the pure reducer can't clear them — any caller that abandons an
     * in-progress stroke (stroke end, mode switch, project load) must invoke this so a later
     * onStrokePoint/onStrokeEnd can't commit to a stale layer or bitmap.
     */
    private fun clearTransientStrokeState() {
        strokeWorkingBitmap = null
        strokeWorkingCanvas = null
        strokePaint = null
        strokePrevBitmapPoint = null
        resetStrokePoints()
        strokeLayerId = null

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
        dispatch(EditorIntent.ShowColorPicker)
    }

    override fun setBrushSize(size: Float) {
        dispatch(EditorIntent.SetBrushSize(size))
    }

    fun setBrushFeathering(amount: Float) {
        dispatch(EditorIntent.SetBrushFeathering(amount))
    }

    override fun setActiveColor(color: Color) {
        dispatch(EditorIntent.SetActiveColor(color))
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
        dispatch(EditorIntent.DismissColorPicker)
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
                _uiState.value.layers.forEach { layerStore.remove(it.id) }
                layerStore.putBase(flatLayer.id, composite.copy(Bitmap.Config.ARGB_8888, false))
                layerStore.initStrokes(flatLayer.id)
                dispatch(EditorIntent.ReplaceLayers(listOf(flatLayer), flatLayer.id))
                saveProject()
            }
        }
    }

    override fun onToggleLinkLayer(layerId: String) {
        pushHistory()
        val groupIds = getLinkedGroupIds(layerId)
        val isPartToUnlink = groupIds.size > 1
        
        _uiState.update { state ->
            val updatedLayers = state.layers.map { layer ->
                if (isPartToUnlink) {
                    // Dissolve the group
                    if (layer.id in groupIds) layer.copy(isLinked = false) else layer
                } else {
                    // Start linking to below
                    if (layer.id == layerId) layer.copy(isLinked = true) else layer
                }
            }
            state.copy(layers = updatedLayers)
        }
        saveProject()
    }

    override fun onToggleVisibility(layerId: String) {
        pushHistory()
        dispatch(EditorIntent.ToggleVisibility(layerId))
        saveProject()
        _uiState.value.layers.find { it.id == layerId }?.let { opEmitter.emit(Op.LayerPropsChange(layerId, it.toLayerProps())) }
    }

    fun setLayers(layers: List<Layer>) {
        dispatch(EditorIntent.SetLayers(layers))
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
            layerStore.putBase(newLayer.id, bitmap.copy(Bitmap.Config.ARGB_8888, false))
            layerStore.initStrokes(newLayer.id)

            withContext(dispatchers.main) {
                dispatch(EditorIntent.AddLayer(newLayer))
                opEmitter.emit(Op.LayerAdd(newLayer))
                // Signal the UI to immediately open this text layer's edit-text box.
                _uiState.update { it.copy(autoEditTextLayerId = newLayer.id) }
                saveProject()
            }
        }
    }

    /** Clear the one-shot auto-edit signal once the UI has opened the text editor. */
    fun consumeAutoEditTextLayer() {
        _uiState.update { it.copy(autoEditTextLayerId = null) }
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
                } catch (e: Exception) {
                    // Don't swallow silently — the layer bitmap is still updated in memory, but a
                    // failed disk write means the text edit won't survive reload.
                    android.util.Log.e("EditorViewModel", "Failed to persist text layer bitmap", e)
                }
            }

            layerStore.putBase(layerId, bitmap.copy(Bitmap.Config.ARGB_8888, false))

            withContext(dispatchers.main) {
                dispatch(EditorIntent.RenderTextLayer(layerId, bitmap, params))
            }
        }
    }

    override fun onTextContentChanged(layerId: String, text: String) {
        val layer = _uiState.value.layers.find { it.id == layerId } ?: return
        val params = layer.textParams ?: return
        pushHistory()
        val updated = params.copy(text = text)
        rerasterizeTextLayer(layerId, updated)
        opEmitter.emit(Op.TextContentChange(layerId, text))
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

    fun updateStencilButtonPosition(position: Offset) {
        dispatch(EditorIntent.SetStencilButtonPosition(position))
    }

    override fun onGenerateStencil(layerId: String) {
        val state = _uiState.value
        val sourceLayer = state.layers.find { it.id == layerId } ?: return
        val projectId = state.projectId ?: return

        pushHistory()
        dispatch(EditorIntent.SetStencilGenerating(true))

        viewModelScope.launch(dispatchers.default) {
            // 1. Identify linked group
            val groupIds = getLinkedGroupIds(layerId)
            val groupLayers = state.layers.filter { it.id in groupIds }
            
            val metrics = context.resources.displayMetrics
            val w = metrics.widthPixels.takeIf { it > 0 } ?: 1080
            val h = metrics.heightPixels.takeIf { it > 0 } ?: 1920
            
            // 2. Generate anchor-relative composite for analysis
            val composite = exportManager.compositeToLayerSpace(sourceLayer, groupLayers, w, h)
            
            // 3. Isolate subject, then show the segmentation slider
            val isolationResult = subjectIsolator.isolate(composite).getOrNull()
            if (isolationResult != null) {
                rawSegmentationConfidence = isolationResult.rawConfidence
                segmentationSourceBitmap = composite
                pendingStencilSourceLayerId = layerId
                pendingStencilProjectId = projectId
                withContext(dispatchers.main) {
                    _uiState.update { it.copy(
                        isStencilGenerating = false, 
                        isSegmenting = true, 
                        segmentationInfluence = 0.5f,
                        segmentationPreview = isolationResult.isolatedBitmap
                    ) }
                }
            } else {
                // Isolation failed — run binary stencil on the raw composite immediately
                runStencilPipeline(composite, layerId, projectId, 0.5f)
            }
        }
    }

    private suspend fun runStencilPipeline(
        isolated: Bitmap,
        sourceLayerId: String,
        projectId: String,
        influence: Float
    ) {
        stencilProcessor.process(isolated, influence).collect { progress ->
            when (progress) {
                is StencilProgress.Done -> {
                    val sourceLayer = _uiState.value.layers.find { it.id == sourceLayerId }
                    val newLayers = progress.layers.map { stencilLayer ->
                        val type = stencilLayer.type
                        val filename = "stencil_${type.name.lowercase()}_${UUID.randomUUID()}.png"
                        val path = projectRepository.saveArtifact(projectId, filename, ImageUtils.bitmapToByteArray(stencilLayer.bitmap))
                        val localUri = "file://$path".toUri()

                        Layer(
                            id = UUID.randomUUID().toString(),
                            name = "Stencil ${type.label}",
                            uri = localUri,
                            bitmap = stencilLayer.bitmap,
                            isLinked = false,
                            stencilType = type,
                            stencilSourceId = sourceLayerId,
                            scale = sourceLayer?.scale ?: 1.0f,
                            offset = sourceLayer?.offset ?: Offset.Zero,
                            rotationX = sourceLayer?.rotationX ?: 0f,
                            rotationY = sourceLayer?.rotationY ?: 0f,
                            rotationZ = sourceLayer?.rotationZ ?: 0f,
                            warpMesh = sourceLayer?.warpMesh ?: emptyList()
                        )
                    }

                    withContext(dispatchers.main) {
                        for (layer in newLayers) {
                            layerStore.putBase(layer.id, layer.bitmap!!.copy(Bitmap.Config.ARGB_8888, false))
                            layerStore.initStrokes(layer.id)
                        }

                        // Set canvas background to match the color of the first generated layer (the Base)
                        val baseColor = if (progress.layers.first().type.color == android.graphics.Color.BLACK) {
                            androidx.compose.ui.graphics.Color.Black
                        } else {
                            androidx.compose.ui.graphics.Color.White
                        }

                        _uiState.update { s ->
                            val idx = s.layers.indexOfFirst { it.id == sourceLayerId }
                            val updatedLayers = s.layers.toMutableList().also { list ->
                                var topIdx = idx
                                while (topIdx + 1 < list.size && list[topIdx + 1].isLinked) topIdx++
                                // Add all new layers in order
                                list.addAll(topIdx + 1, newLayers)
                            }
                            s.copy(
                                layers = updatedLayers,
                                activeLayerId = newLayers.last().id,
                                isStencilGenerating = false,
                                stencilHintVisible = true,
                                canvasBackground = baseColor
                            )
                        }
                        viewModelScope.launch {
                            kotlinx.coroutines.delay(3000)
                            dispatch(EditorIntent.SetStencilHintVisible(false))
                        }
                        saveProject()
                    }
                }
                is StencilProgress.Error -> {
                    withContext(dispatchers.main) {
                        dispatch(EditorIntent.SetStencilGenerating(false))
                        Toast.makeText(context, "Stencil failed: ${progress.message}", Toast.LENGTH_LONG).show()
                    }
                }
                else -> { /* Progress updates handled by UI if needed */ }
            }
        }
    }

    override fun onGeneratePoster(layerId: String) {
        // This is called from the PosterOptionsDialog
    }

    // -------------------------------------------------------------------------
    // Co-op spectator API
    // -------------------------------------------------------------------------

    /** Applies a remote Op received from the host, without echoing it back through opEmitter. */
    fun applySpectatorOp(op: Op) {
        when (op) {
            is Op.LayerAdd -> dispatch(EditorIntent.AppendLayer(op.layer))
            is Op.LayerRemove -> dispatch(EditorIntent.RemoveLayerById(op.layerId))
            is Op.LayerReorder -> dispatch(EditorIntent.ReorderLayers(op.newOrder))
            is Op.LayerTransform -> {
                // The host encodes transform as [scale, offsetX, offsetY, rotX, rotY, rotZ, 0...0].
                // Apply the first 6 slots back to the matching layer.
                if (op.matrix.size >= 6) {
                    dispatch(EditorIntent.SetLayerTransformById(
                        op.layerId,
                        scale = op.matrix[0],
                        offset = androidx.compose.ui.geometry.Offset(op.matrix[1], op.matrix[2]),
                        rx = op.matrix[3], ry = op.matrix[4], rz = op.matrix[5],
                    ))
                }
            }
            is Op.LayerPropsChange -> dispatch(EditorIntent.SetLayerProps(op.layerId, op.props))
            is Op.StrokeComplete -> {
                val layerId = op.layerId
                val stroke = op.stroke
                val layer = _uiState.value.layers.find { it.id == layerId } ?: return
                
                viewModelScope.launch(dispatchers.default) {
                    val points = mutableListOf<Offset>()
                    for (i in 0 until stroke.points.size step 2) {
                        points.add(Offset(stroke.points[i], stroke.points[i+1]))
                    }
                    
                    val tool = Tool.entries.getOrNull(stroke.blendModeOrdinal) ?: Tool.BRUSH
                    val bitmap = layer.bitmap ?: return@launch
                    
                    // The points are already in BITMAP space (mapped by the host).
                    // To bypass mapping in DrawingEngine, we set canvasSize to bitmap size
                    // and identity transform.
                    val command = StrokeCommand(
                        path = points,
                        canvasSize = IntSize(bitmap.width, bitmap.height),
                        tool = tool,
                        brushSize = stroke.brushSize,
                        brushColor = stroke.colorArgb.toInt(),
                        intensity = 0.5f,
                        feathering = stroke.brushFeathering,
                        layerScale = 1f,
                        layerOffset = Offset.Zero,
                        layerRotationZ = 0f
                    )
                    
                    layerStore.addStroke(layerId, command)
                    rebuildLayerBitmap(layerId)
                }
            }
            is Op.TextContentChange -> {
                // rerasterizeTextLayer launches its own coroutine and updates state itself,
                // so it must NOT run inside _uiState.update { } — that lambda can re-run under
                // CAS contention and would launch duplicate rasterizations racing the same file.
                val updatedParams = _uiState.value.layers
                    .find { it.id == op.layerId }?.textParams?.copy(text = op.text)
                if (updatedParams != null) {
                    rerasterizeTextLayer(op.layerId, updatedParams)
                }
            }
            is Op.LayerBitmapReplace -> {
                val layerId = op.layerId
                if (_uiState.value.layers.none { it.id == layerId }) return
                viewModelScope.launch(dispatchers.default) {
                    // Cap the decoded bitmap at 2x the longest screen edge — plenty for any layer
                    // that reasonably rasterises to a screen quad, and prevents a peer accidentally
                    // shipping a giant PNG from OOMing the guest. Log-and-skip on decode failure
                    // rather than throwing across the op-apply.
                    val metrics = context.resources.displayMetrics
                    val maxDim = maxOf(metrics.widthPixels, metrics.heightPixels) * 2
                    val decoded = decodeBoundedBitmap(op.png, maxDim) ?: run {
                        android.util.Log.w(
                            "EditorViewModel",
                            "LayerBitmapReplace: skipping op for layer $layerId (decode returned null; bytes=${op.png.size})"
                        )
                        return@launch
                    }
                    val base = decoded.copy(Bitmap.Config.ARGB_8888, false)
                    if (base != decoded) decoded.recycle()
                    // Re-check the layer still exists — it can be removed while we were decoding
                    // off-thread. Without this, `putBase` on a stale layerId would leak the base
                    // pixel memory (nothing takes ownership of it).
                    if (_uiState.value.layers.none { it.id == layerId }) {
                        base.recycle()
                        return@launch
                    }
                    // The png is the full baked layer; replace base and drop local stroke history.
                    layerStore.putBase(layerId, base)
                    layerStore.initStrokes(layerId)
                    rebuildLayerBitmap(layerId)
                }
            }
        }
    }

    // NOTE: spectator/guest project loading is handled by ProjectManager.loadAsSpectator (unzip →
    // createProject → currentProject emission), which this ViewModel's currentProject collector
    // already reacts to by loading the project's layers. The former loadAsSpectator stub here was
    // dead code from Task 14 — never called — and implied a second, unimplemented load path.

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

        dispatch(EditorIntent.SetLoading(true))
        viewModelScope.launch(dispatchers.io) {
            val result = stencilPrintEngine.generatePdf(
                context,
                stencilLayers,
                outputSizeMm,
                StencilOutputDimension.WIDTH // Default to width for now
            )
            
            withContext(dispatchers.main) {
                dispatch(EditorIntent.SetLoading(false))
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
