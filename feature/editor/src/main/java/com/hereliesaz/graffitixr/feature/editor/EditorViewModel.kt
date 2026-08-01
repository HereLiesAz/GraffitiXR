// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorViewModel.kt
package com.hereliesaz.graffitixr.feature.editor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.common.DispatcherProvider
import com.hereliesaz.graffitixr.common.coop.OpEmitter
import com.hereliesaz.graffitixr.common.model.*
import com.hereliesaz.graffitixr.common.util.ImageUtils
import com.hereliesaz.graffitixr.common.util.computeAutoTune
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import androidx.core.net.toUri

/**
 * Owns the overlay document and its placement.
 *
 * This app's job is getting an image into place for tracing — by lightbox (Trace / Overlay /
 * Mockup) or by projection (AR). Authoring the image belongs to the companion design app, so the
 * painting, stencil, outline-extraction, subject-isolation and text-authoring pipelines that used
 * to live here have been removed; images arrive already finished, via the picker or an inbound
 * ACTION_SEND share, and leave via [exportImage] / [exportForShare].
 *
 * What remains is placement (transform, warp, lock, layer order) and legibility (opacity,
 * brightness, contrast, saturation, colour balance, invert) — the controls that make an overlay
 * usable against a real wall.
 */
@HiltViewModel
class EditorViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val settingsRepository: SettingsRepository,
    private val projectManager: ProjectManager,
    private val exportManager: ExportManager,
    @ApplicationContext private val context: Context,
    internal val slamManager: SlamManager,
    private val dispatchers: DispatcherProvider,
    private val opEmitter: OpEmitter,
) : ViewModel(), EditorActions {

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState = _uiState.asStateFlow()

    /**
     * Per-host AzNavRail expansion state (host id -> expanded), surfaced from the current project so the
     * rail can restore exactly as the user left it on reopen.
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

    /** Persist a host item's expanded/collapsed state into the project record so it survives reopen. */
    fun onRailHostExpansionChanged(hostId: String, expanded: Boolean) {
        viewModelScope.launch(dispatchers.io) {
            projectRepository.updateProject { it.copy(railExpansion = it.railExpansion + (hostId to expanded)) }
        }
    }

    private val history = EditHistory()

    // Debounced project-preview thumbnail generation. saveProject() fires on nearly every edit,
    // so the thumbnail is regenerated at most once the edits settle, off the main thread.
    private var thumbnailJob: kotlinx.coroutines.Job? = null

    private var anchorHalfExtentMeters: Pair<Float, Float>? = null

    init {
        viewModelScope.launch(dispatchers.main) {
            settingsRepository.backgroundColor.collect { argb ->
                dispatch(EditorIntent.SetCanvasBackground(Color(argb.toLong() and 0xFFFFFFFFL)))
            }
        }

        viewModelScope.launch(dispatchers.main) {
            projectRepository.currentProject.collect { project ->
                if (project != null) {
                    if (_uiState.value.projectId != project.id) loadProject(project)
                } else {
                    dispatch(EditorIntent.ClearProject)
                    slamManager.clearMap()
                    history.clear()
                }
            }
        }
    }

    private fun loadProject(project: GraffitiProject) {
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

        if (layers.any { it.bitmap == null && it.uri != null }) {
            viewModelScope.launch(dispatchers.io) {
                val loadedLayers = layers.map { layer ->
                    val layerUri = layer.uri
                    if (layer.bitmap == null && layerUri != null) {
                        layer.copy(bitmap = ImageUtils.loadBitmapAsync(context, layerUri))
                    } else {
                        layer
                    }
                }
                withContext(dispatchers.main) { dispatch(EditorIntent.SetLayers(loadedLayers)) }
            }
        }

        viewModelScope.launch(dispatchers.io) { restoreWorld(project) }

        project.backgroundImageUri?.let { uri ->
            viewModelScope.launch(dispatchers.io) {
                val bitmap = ImageUtils.loadBitmapAsync(context, uri)
                withContext(dispatchers.main) { dispatch(EditorIntent.SetBackgroundBitmap(bitmap)) }
            }
        }
    }

    /** Restores the SLAM map and wall fingerprint so AR relocalizes against the saved target. */
    private fun restoreWorld(project: GraffitiProject) {
        slamManager.clearMap()
        val mapPath = projectManager.getMapPath(context, project.id)
        if (File(mapPath).exists()) slamManager.loadModel(mapPath)

        val fp = project.fingerprint ?: return
        val intr = project.fingerprintIntrinsics
        val anchor = project.fingerprintAnchor
        if (intr.size >= 4 && anchor.size == 16) {
            // Metric fingerprint: replay the true capture intrinsics + anchor so reload reloc
            // matches the live capture, not a default guess.
            slamManager.restoreWallFingerprintMetric(
                fp.descriptorsData, fp.descriptorsRows, fp.descriptorsCols, fp.descriptorsType,
                fp.points3d.toFloatArray(), anchor.toFloatArray(), intr.toFloatArray(),
                // Capture view, so reload keeps the plane-guided rectification for oblique views.
                // Empty on projects saved before it was persisted — native then skips that pass.
                viewMatrix = project.fingerprintViewMatrix
                    .takeIf { it.size == 16 }?.toFloatArray() ?: FloatArray(0),
            )
        } else {
            slamManager.restoreWallFingerprint(
                fp.descriptorsData, fp.descriptorsRows, fp.descriptorsCols, fp.descriptorsType,
                fp.points3d.toFloatArray()
            )
        }
        // Restore the distortion-head canonical patch (256x256 raw gray).
        if (fp.patchData.isNotEmpty()) {
            val s = kotlin.math.sqrt(fp.patchData.size.toDouble()).toInt()
            if (s * s == fp.patchData.size) slamManager.setWallPatchBytes(fp.patchData, s)
        }
    }

    fun setEditorMode(mode: EditorMode) = dispatch(EditorIntent.SetEditorMode(mode))

    // ── Undo / redo ───────────────────────────────────────────────────────────

    private fun pushHistory() {
        history.pushProperty(currentLayerSnapshot())
        updateHistoryCounts()
    }

    private fun updateHistoryCounts() {
        _uiState.update { it.copy(undoCount = history.undoCount, redoCount = history.redoCount) }
    }

    /** The current layer set, stripped of bitmaps — what we record so an undo can be reverted. */
    private fun currentLayerSnapshot(): List<Layer> = _uiState.value.layers.map { it.copy(bitmap = null) }

    override fun onUndoClicked() = applyHistory(history.popUndo { EditCommand(currentLayerSnapshot()) })

    override fun onRedoClicked() = applyHistory(history.popRedo { EditCommand(currentLayerSnapshot()) })

    private fun applyHistory(command: EditCommand?) {
        command ?: return
        // Bitmaps are transient and identical across a property-only change, so carry the live ones
        // over rather than reloading them from disk.
        val currentBitmaps = _uiState.value.layers.associate { it.id to it.bitmap }
        val restoredLayers = command.oldLayers.map { it.copy(bitmap = currentBitmaps[it.id]) }
        dispatch(EditorIntent.SetLayers(restoredLayers))
        saveProject()
        emitLayerStateResync(restoredLayers)
        updateHistoryCounts()
    }

    // ── Layers ────────────────────────────────────────────────────────────────

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
                // Fit the imported image to the screen so it lands somewhere usable.
                val initialScale = minOf(screenW * 0.9f / bitmap.width, screenH * 0.9f / bitmap.height, 1.0f)

                val newLayer = Layer(
                    id = UUID.randomUUID().toString(),
                    name = "Layer ${_uiState.value.layers.size + 1}",
                    uri = localUri,
                    bitmap = bitmap,
                    isVisible = true,
                    scale = initialScale
                )

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

    override fun onLayerActivated(id: String) = dispatch(EditorIntent.ActivateLayer(id))

    override fun onLayerRemoved(id: String) {
        pushHistory()
        dispatch(EditorIntent.RemoveLayer(id))
        opEmitter.emit(Op.LayerRemove(id))
        saveProject()
    }

    override fun onLayerReordered(newOrder: List<String>) {
        pushHistory()
        dispatch(EditorIntent.ReorderLayers(newOrder))
        opEmitter.emit(Op.LayerReorder(newOrder))
        saveProject()
    }

    override fun onLayerRenamed(id: String, name: String) {
        pushHistory()
        dispatch(EditorIntent.RenameLayer(id, name))
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

    // ── Background (Mockup wall photo) ────────────────────────────────────────

    fun setBackgroundImage(uri: Uri) {
        val projectId = _uiState.value.projectId ?: return
        viewModelScope.launch(dispatchers.io) {
            dispatch(EditorIntent.SetLoading(true))
            val bitmap = ImageUtils.loadBitmapAsync(context, uri)
            if (bitmap != null) {
                val filename = "bg_${UUID.randomUUID()}.png"
                val path = projectRepository.saveArtifact(projectId, filename, ImageUtils.bitmapToByteArray(bitmap))
                projectRepository.updateProject { it.copy(backgroundImageUri = "file://$path".toUri()) }
                withContext(dispatchers.main) {
                    dispatch(EditorIntent.SetBackgroundBitmap(bitmap))
                    dispatch(EditorIntent.SetLoading(false))
                }
            } else {
                withContext(dispatchers.main) { dispatch(EditorIntent.SetLoading(false)) }
            }
        }
    }

    /** Remove the Mockup wall photo: clears the persisted background URI and the live bitmap. */
    fun clearBackgroundImage() {
        viewModelScope.launch(dispatchers.io) {
            projectRepository.updateProject { it.copy(backgroundImageUri = null) }
            withContext(dispatchers.main) { dispatch(EditorIntent.SetBackgroundBitmap(null)) }
        }
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    fun saveProject(name: String? = null) {
        viewModelScope.launch(dispatchers.io) {
            try {
                val currentProject = projectRepository.currentProject.value
                val updatedLayers = _uiState.value.layers.map { it.toOverlayLayer() }
                val modeAdjustments = _uiState.value.modeAdjustments.mapKeys { it.key.name }

                // Paths derive from the (immutable) project id. Persist the SLAM world first so they're valid.
                val projectId = currentProject?.id ?: GraffitiProject(name = name ?: "New Project").id
                val mapPath = projectManager.getMapPath(context, projectId)
                val cloudPointsPath = projectManager.getCloudPointsPath(context, projectId)
                slamManager.saveModel(mapPath)

                val manifestToSave: GraffitiProject
                if (currentProject == null) {
                    manifestToSave = GraffitiProject(
                        id = projectId,
                        name = name ?: "New Project",
                        layers = updatedLayers,
                        modeAdjustments = modeAdjustments,
                        mapPath = mapPath,
                        cloudPointsPath = cloudPointsPath,
                    )
                    projectRepository.createProject(manifestToSave)
                } else {
                    // Atomic read-modify-write: a concurrent AR wall-feature-map save merges into the SAME
                    // currentProject, so writing a full stale copy here would drop its wall map (and vice
                    // versa). The transform only touches the editor-owned fields.
                    projectRepository.updateProject { current ->
                        current.copy(
                            name = name ?: current.name,
                            layers = updatedLayers,
                            modeAdjustments = modeAdjustments,
                            lastModified = System.currentTimeMillis(),
                            mapPath = mapPath,
                            cloudPointsPath = cloudPointsPath,
                        )
                    }
                    // Export the merged result the repository just persisted (includes any AR wall map).
                    manifestToSave = projectRepository.currentProject.value ?: return@launch
                }

                if (name != null) exportProjectInternal(manifestToSave)

                scheduleThumbnailUpdate()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
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
     * stream of autosaves doesn't composite on every adjustment.
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
                    if (e is kotlinx.coroutines.CancellationException) throw e
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
                projectManager.exportProjectToUri(context, project.id, Uri.fromFile(file))
                withContext(dispatchers.main) {
                    Toast.makeText(context, "Project saved and exported to ${file.absolutePath}", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            withContext(dispatchers.main) {
                Toast.makeText(context, "Project saved locally. Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Export / interop ──────────────────────────────────────────────────────

    /**
     * Export the current view as a PNG saved to the gallery.
     *
     * @param backgroundBitmap When non-null, used as the export's background (Overlay: the CameraX
     *   still; AR: the composited GL framebuffer readback that already includes the wall-anchored
     *   overlay). When null, per-mode default applies.
     * @param skipLayerComposite When true, the [backgroundBitmap] IS the export — no layers are
     *   drawn on top. Set by the AR path because the GL readback already contains the layers as
     *   the wall-anchored quad; drawing them again would double-draw.
     */
    fun exportImage(backgroundBitmap: Bitmap? = null, skipLayerComposite: Boolean = false) {
        viewModelScope.launch(dispatchers.default) {
            dispatch(EditorIntent.SetLoading(true))
            try {
                val exportBitmap = if (skipLayerComposite && backgroundBitmap != null) {
                    backgroundBitmap
                } else {
                    val metrics = context.resources.displayMetrics
                    val bgBmp = backgroundBitmap
                        ?: if (_uiState.value.editorMode == EditorMode.MOCKUP) _uiState.value.backgroundBitmap else null
                    exportManager.compositeLayers(
                        _uiState.value.layers,
                        metrics.widthPixels.takeIf { it > 0 } ?: 1080,
                        metrics.heightPixels.takeIf { it > 0 } ?: 1920,
                        backgroundBitmap = bgBmp,
                        backgroundColor = android.graphics.Color.TRANSPARENT,
                    )
                }

                val success = saveBitmapToGallery(context, exportBitmap)

                withContext(dispatchers.main) {
                    dispatch(EditorIntent.SetLoading(false))
                    Toast.makeText(
                        context,
                        if (success) "Image saved to gallery" else "Failed to save image",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                withContext(dispatchers.main) {
                    dispatch(EditorIntent.SetLoading(false))
                    Toast.makeText(context, "Export error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Composites the current layers to a PNG in `cacheDir/shared` and returns a FileProvider
     * `content://` Uri suitable for `ACTION_SEND` — the two-app hand-off back to the design app.
     * Returns null if there's nothing to share. The host fires the share intent; the Uri authority
     * is `${applicationId}.fileprovider`, declared in the manifest.
     */
    suspend fun exportForShare(): Uri? = withContext(dispatchers.default) {
        val layers = _uiState.value.layers
        if (layers.isEmpty()) return@withContext null
        val metrics = context.resources.displayMetrics
        val composite = exportManager.compositeLayers(
            layers,
            metrics.widthPixels.takeIf { it > 0 } ?: 1080,
            metrics.heightPixels.takeIf { it > 0 } ?: 1920,
            backgroundColor = android.graphics.Color.TRANSPARENT,
        )
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "graffitixr_share.png")
        java.io.FileOutputStream(file).use { out ->
            composite.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        composite.recycle()
        androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    // ── Settings / perception layers ──────────────────────────────────────────

    fun toggleHandedness() = dispatch(EditorIntent.ToggleHandedness)
    fun toggleDiagOverlay() = dispatch(EditorIntent.ToggleDiagOverlay)
    fun toggleFeaturePoints() = dispatch(EditorIntent.ToggleFeaturePoints)
    fun togglePlaneGrids() = dispatch(EditorIntent.TogglePlaneGrids)
    fun togglePoints() = dispatch(EditorIntent.TogglePoints)

    // ── Placement ─────────────────────────────────────────────────────────────

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

    fun onModeTransformGesture(mode: EditorMode, pan: Offset, zoom: Float, rotationDelta: Float) {
        dispatch(EditorIntent.ApplyModeTransformGesture(mode, pan, zoom, rotationDelta))
    }

    /** Toggle the per-mode transform lock — pins/unpins the whole-design position for [mode]. */
    fun onToggleModeTransformLocked(mode: EditorMode) {
        dispatch(EditorIntent.ToggleModeTransformLocked(mode))
        saveProject()
    }

    override fun onGestureStart() {
        pushHistory()
        dispatch(EditorIntent.BeginGesture)
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
        opEmitter.emit(Op.LayerTransform(activeId, layer.encodeTransform()))
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

    override fun onScaleChanged(s: Float) = dispatch(EditorIntent.SetScale(s))
    override fun onOffsetChanged(o: Offset) = dispatch(EditorIntent.AddOffset(o))
    override fun onRotationXChanged(d: Float) = dispatch(EditorIntent.SetRotationX(d))
    override fun onRotationYChanged(d: Float) = dispatch(EditorIntent.SetRotationY(d))
    override fun onRotationZChanged(d: Float) = dispatch(EditorIntent.SetRotationZ(d))
    override fun onCycleRotationAxis() = dispatch(EditorIntent.CycleRotationAxis)

    override fun setLayerTransform(scale: Float, offset: Offset, rx: Float, ry: Float, rz: Float) {
        dispatch(EditorIntent.SetLayerTransform(scale, offset, rx, ry, rz))
        saveProject()
    }

    override fun onLayerWarpChanged(layerId: String, mesh: List<Float>) {
        dispatch(EditorIntent.SetLayerWarp(layerId, mesh))
        saveProject()
    }

    // ── Legibility ────────────────────────────────────────────────────────────

    override fun onAdjustmentStart() {
        pushHistory()
        dispatch(EditorIntent.SetGestureInProgress(true))
    }

    override fun onAdjustmentEnd() {
        dispatch(EditorIntent.SetGestureInProgress(false))
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
     * First-run doodle demo: on the scribble->artwork swap, pre-set the adjustment knobs to values
     * that read well against the wall. A starting point the user then fine-tunes — not a hard grade.
     */
    fun autoTuneActiveLayer(wall: com.hereliesaz.graffitixr.common.util.ImageStats?) {
        if (wall == null) return
        val bitmap = _uiState.value.layers.find { it.id == _uiState.value.activeLayerId }?.bitmap ?: return
        viewModelScope.launch(dispatchers.default) {
            val t = computeAutoTune(wall, bitmap.imageStats())
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

    override fun onFeedbackShown() = dispatch(EditorIntent.FeedbackShown)

    // ── Co-op ─────────────────────────────────────────────────────────────────

    /** Re-pushes layer order, props and transforms to guests after an undo/redo. */
    private fun emitLayerStateResync(layers: List<Layer>) {
        opEmitter.emit(Op.LayerReorder(layers.map { it.id }))
        layers.forEach { l ->
            opEmitter.emit(Op.LayerPropsChange(l.id, l.toLayerProps()))
            opEmitter.emit(Op.LayerTransform(l.id, l.encodeTransform()))
        }
    }

    /** Emits a co-op LayerPropsChange for the active layer, if any. */
    private fun emitActiveLayerProps() {
        val id = _uiState.value.activeLayerId ?: return
        _uiState.value.layers.find { it.id == id }?.let { opEmitter.emit(Op.LayerPropsChange(id, it.toLayerProps())) }
    }

    private fun Layer.encodeTransform() = listOf(
        scale, offset.x, offset.y, rotationX, rotationY, rotationZ,
        0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f
    )

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

    /** Applies a remote Op received from the host, without echoing it back through opEmitter. */
    fun applySpectatorOp(op: Op) {
        when (op) {
            is Op.LayerAdd -> dispatch(EditorIntent.AppendLayer(op.layer))
            is Op.LayerRemove -> dispatch(EditorIntent.RemoveLayerById(op.layerId))
            is Op.LayerReorder -> dispatch(EditorIntent.ReorderLayers(op.newOrder))
            is Op.LayerTransform -> {
                // The host encodes transform as [scale, offsetX, offsetY, rotX, rotY, rotZ, 0...0].
                if (op.matrix.size >= 6) {
                    dispatch(EditorIntent.SetLayerTransformById(
                        op.layerId,
                        scale = op.matrix[0],
                        offset = Offset(op.matrix[1], op.matrix[2]),
                        rx = op.matrix[3], ry = op.matrix[4], rz = op.matrix[5],
                    ))
                }
            }
            is Op.LayerPropsChange -> dispatch(EditorIntent.SetLayerProps(op.layerId, op.props))
            is Op.LayerBitmapReplace -> {
                val layerId = op.layerId
                if (_uiState.value.layers.none { it.id == layerId }) return
                viewModelScope.launch(dispatchers.default) {
                    // Cap the decoded bitmap at 2x the longest screen edge — plenty for any layer
                    // that reasonably rasterises to a screen quad, and prevents a peer accidentally
                    // shipping a giant PNG from OOMing the guest.
                    val metrics = context.resources.displayMetrics
                    val maxDim = maxOf(metrics.widthPixels, metrics.heightPixels) * 2
                    val decoded = com.hereliesaz.graffitixr.common.util.decodeBoundedBitmap(op.png, maxDim) ?: run {
                        android.util.Log.w(
                            "EditorViewModel",
                            "LayerBitmapReplace: skipping op for layer $layerId (decode returned null; bytes=${op.png.size})"
                        )
                        return@launch
                    }
                    withContext(dispatchers.main) {
                        _uiState.update { s ->
                            s.copy(layers = s.layers.map { if (it.id == layerId) it.copy(bitmap = decoded) else it })
                        }
                    }
                }
            }
            // Authoring ops a peer running the design-side build may still send. This app no longer
            // edits pixels or text, so there is nothing to apply — accepted and ignored rather than
            // breaking the session over a frame it merely doesn't use.
            is Op.StrokeComplete -> Unit
            is Op.TextContentChange -> Unit
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun updateActiveLayer(transform: (Layer) -> Layer) {
        _uiState.update { state ->
            val id = state.activeLayerId ?: return@update state
            state.copy(layers = LayerListOps.mapLayer(state.layers, id, transform))
        }
    }

    fun updateAllLayers(transform: (Layer) -> Layer) {
        _uiState.update { state -> state.copy(layers = state.layers.map(transform)) }
    }

    /**
     * MVI dispatch: apply a state-only [EditorIntent] through the pure [EditorReducer]. Side
     * effects (history, persistence, co-op op emission) are orchestrated by the caller around
     * this call — the reducer itself stays pure.
     */
    private fun dispatch(intent: EditorIntent) {
        _uiState.update { EditorReducer.reduce(it, intent) }
    }

    /** Returns the IDs of all layers in the same link-group as [layerId].
     *  A group is a contiguous run where each layer above the bottom has isLinked = true. */
    private fun getLinkedGroupIds(layerId: String): Set<String> {
        val layers = _uiState.value.layers
        val idx = layers.indexOfFirst { it.id == layerId }
        if (idx < 0) return setOf(layerId)
        var bottom = idx
        while (bottom > 0 && layers[bottom].isLinked) bottom--
        var top = idx
        while (top + 1 < layers.size && layers[top + 1].isLinked) top++
        return layers.subList(bottom, top + 1).map { it.id }.toSet()
    }

    private fun updateLinkedGroup(activeId: String, transform: (Layer) -> Layer) {
        val groupIds = getLinkedGroupIds(activeId)
        _uiState.update { state -> state.copy(layers = state.layers.map { if (it.id in groupIds) transform(it) else it }) }
    }
}
