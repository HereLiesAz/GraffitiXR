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
import com.hereliesaz.graffitixr.common.util.SketchProcessor
import com.hereliesaz.graffitixr.common.util.computeAutoTune
import com.hereliesaz.graffitixr.common.util.imageStats
import com.hereliesaz.graffitixr.common.util.saveBitmapToGallery
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
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
 * painting, stencil and text-authoring pipelines that used to live here have been removed; images
 * arrive already finished, via the picker or an inbound ACTION_SEND share, and leave via
 * [exportImage] / [exportForShare].
 *
 * What remains is placement (transform, lock) and legibility — opacity, brightness, contrast,
 * saturation, colour balance, invert, plus the two effects that change what the image IS rather
 * than how it is toned: Outline ([onToggleOutline]) and subject isolation
 * ([onToggleSubjectIsolation]). Those two are here rather than in the design app because they serve
 * tracing specifically: an outline is the form you trace, and isolation removes a background that
 * would otherwise be projected onto the wall.
 */
@HiltViewModel
class EditorViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val settingsRepository: SettingsRepository,
    private val projectManager: ProjectManager,
    private val exportManager: ExportManager,
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
    private val opEmitter: OpEmitter,
    private val subjectIsolator: SubjectIsolator,
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

    private var railExpansionJob: kotlinx.coroutines.Job? = null
    private val pendingRailExpansion = mutableMapOf<String, Boolean>()

    /**
     * Persist a host item's expanded/collapsed state into the project record so it survives reopen.
     *
     * Debounced: every write goes through [ProjectRepository.updateProject], which unconditionally
     * saves the whole manifest AND rescans the entire project library — appropriate for an actual
     * edit, wasteful for what's purely a UI preference. Undebounced, tapping through the rail's four
     * host folders was a full save-and-rescan burst, one per tap. Called only from the UI thread, so
     * [pendingRailExpansion] needs no synchronization.
     */
    fun onRailHostExpansionChanged(hostId: String, expanded: Boolean) {
        pendingRailExpansion[hostId] = expanded
        railExpansionJob?.cancel()
        railExpansionJob = viewModelScope.launch(dispatchers.main) {
            kotlinx.coroutines.delay(500)
            val toWrite = pendingRailExpansion.toMap()
            pendingRailExpansion.clear()
            if (toWrite.isEmpty()) return@launch
            withContext(dispatchers.io) {
                projectRepository.updateProject { it.copy(railExpansion = it.railExpansion + toWrite) }
            }
        }
    }

    private val history = EditHistory()

    // Debounced project-preview thumbnail generation. saveProject() fires on nearly every edit,
    // so the thumbnail is regenerated at most once the edits settle, off the main thread.
    private var thumbnailJob: kotlinx.coroutines.Job? = null

    private var anchorHalfExtentMeters: Pair<Float, Float>? = null

    /**
     * The design exactly as imported, before Outline or subject isolation.
     *
     * The effects are toggles, so they must be reversible without loss: re-deriving from this each
     * time is what makes turning one off give back the original rather than an image that has been
     * through the pipeline twice. Kept off [EditorUiState] because it is a decode cache, not state
     * the UI reads — the persisted `uri` is the durable copy and this is refilled from it on load.
     */
    private var designSourceBitmap: Bitmap? = null

    /** Cancels a superseded effect recompute, so rapid toggling doesn't pile up full-image work. */
    private var designEffectJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch(dispatchers.main) {
            settingsRepository.backgroundColor.collect { argb ->
                dispatch(EditorIntent.SetCanvasBackground(Color(argb.toLong() and 0xFFFFFFFFL)))
            }
        }

        // Dominant hand is a device/user-level preference (SettingsRepository), not per-project —
        // an artist doesn't have a different dominant hand per project. Restoring it here on every
        // launch is what makes the Settings toggle survive a restart; without this the reducer's
        // ToggleHandedness only ever flipped an in-memory EditorUiState field that started from the
        // hardcoded default every time the process was recreated. Kept as a live collector (not a
        // one-shot read) so a change made from Settings is reflected immediately if this ViewModel
        // is still alive, matching the backgroundColor collector just above.
        viewModelScope.launch(dispatchers.main) {
            settingsRepository.isRightHanded.collect { isRight ->
                _uiState.update { it.copy(isRightHanded = isRight) }
            }
        }

        // The four perception-debug overlays below this comment used to only dispatch an in-memory
        // reducer intent — indistinguishable in Settings from isRightHanded just above, which
        // genuinely persists, until the process was killed and every one of them silently reverted
        // to its hardcoded default. Restored the same way: a live collector, not a one-shot read.
        viewModelScope.launch(dispatchers.main) {
            settingsRepository.showDiagOverlay.collect { on ->
                _uiState.update { it.copy(showDiagOverlay = on) }
            }
        }
        viewModelScope.launch(dispatchers.main) {
            settingsRepository.showFeaturePoints.collect { on ->
                _uiState.update { it.copy(showFeaturePoints = on) }
            }
        }
        viewModelScope.launch(dispatchers.main) {
            settingsRepository.showPlaneGrids.collect { on ->
                _uiState.update { it.copy(showPlaneGrids = on) }
            }
        }
        viewModelScope.launch(dispatchers.main) {
            settingsRepository.showPoints.collect { on ->
                _uiState.update { it.copy(showPoints = on) }
            }
        }

        viewModelScope.launch(dispatchers.main) {
            projectRepository.currentProject.collect { project ->
                if (project != null) {
                    if (_uiState.value.projectId != project.id) loadProject(project)
                } else {
                    dispatch(EditorIntent.ClearProject)
                    history.clear()
                }
            }
        }
    }

    private fun loadProject(project: GraffitiProject) {
        // This only runs on a genuine project switch (the caller already checked projectId
        // changed) — so anything scoped to the PREVIOUS project must be invalidated here, or it
        // leaks into the new one. Undo history is the sharpest case: left uncleared, pressing
        // Undo in the new project restored and persisted the OLD project's design (it was never
        // cleared anywhere but the null-project path, which a switch never hits). The decoded
        // "before effects" cache and the AR target extent from Magic are the same class of bug —
        // silently applying stale, previous-project state to the one now on screen.
        history.clear()
        updateHistoryCounts()
        designSourceBitmap = null
        anchorHalfExtentMeters = null

        val current = _uiState.value.design
        val loaded = project.design?.toLayer()?.let { design ->
            // Carry the live bitmap over when the image is unchanged, so reopening the same project
            // does not re-decode it off disk.
            if (current != null && current.uri == design.uri) design.copy(bitmap = current.bitmap) else design
        }

        dispatch(EditorIntent.LoadedProject(project.id, loaded))

        val loadedModeAdjustments = project.modeAdjustments.mapNotNull { (key, value) ->
            runCatching { EditorMode.valueOf(key) }.getOrNull()?.let { it to value }
        }.toMap()
        dispatch(EditorIntent.SetAllModeAdjustments(loadedModeAdjustments))

        val pendingUri = loaded?.takeIf { it.bitmap == null }?.uri
        if (pendingUri != null) {
            viewModelScope.launch(dispatchers.io) {
                // uri is always the untouched import, so this is the effect source; the saved
                // Outline / isolation flags are then re-derived on top of it.
                val source = ImageUtils.loadBitmapAsync(context, pendingUri)
                // Project-load restore, not a fresh user toggle -- don't surface the failure toast
                // here even if a stage falls back; recomputeDesignEffects handles the live-toggle
                // case where the user just took an action and needs to see it didn't apply.
                val shown = source?.let { applyDesignEffects(it, loaded).first }
                withContext(dispatchers.main) {
                    designSourceBitmap = source
                    dispatch(EditorIntent.RestoreDesign(loaded.copy(bitmap = shown)))
                }
            }
        }

        // Wall-fingerprint restore is ArViewModel's job (loadFingerprintIfExists) — it owns the
        // SlamManager singleton and does the partition/legacy-frame/design-placement handling that
        // this class does not replicate. A second loader here used to race it: same currentProject
        // flow, same native engine, undefined order, and this one skipped every one of those steps.

        project.backgroundImageUri?.let { uri ->
            viewModelScope.launch(dispatchers.io) {
                val bitmap = ImageUtils.loadBitmapAsync(context, uri)
                withContext(dispatchers.main) { dispatch(EditorIntent.SetBackgroundBitmap(bitmap)) }
            }
        }
    }

    fun setEditorMode(mode: EditorMode) = dispatch(EditorIntent.SetEditorMode(mode))

    // ── Undo / redo ───────────────────────────────────────────────────────────

    private fun pushHistory() {
        history.pushProperty(currentDesignSnapshot(), currentSnapshotMode(), currentModeAdjustmentSnapshot())
        updateHistoryCounts()
    }

    private fun updateHistoryCounts() {
        _uiState.update { it.copy(undoCount = history.undoCount, redoCount = history.redoCount) }
    }

    /** The design, stripped of its bitmap — what we record so an undo can be reverted. */
    private fun currentDesignSnapshot(): Layer? = _uiState.value.design?.copy(bitmap = null)

    /**
     * The mode a snapshot is being taken in — null in DESIGN mode, where there is no whole-design
     * adjustment to capture (design-mode edits go straight to the design layer, already covered by
     * [currentDesignSnapshot]).
     */
    private fun currentSnapshotMode(): EditorMode? =
        _uiState.value.editorMode.takeIf { it != EditorMode.DESIGN }

    /**
     * The active mode's [ModeAdjustment], if the snapshot is being taken outside DESIGN — every
     * gesture and tone control there writes here, not to the design, so it has to travel with the
     * design snapshot for Undo to restore what actually changed.
     */
    private fun currentModeAdjustmentSnapshot(): ModeAdjustment? =
        currentSnapshotMode()?.let { _uiState.value.modeAdjustments[it] ?: ModeAdjustment() }

    private fun currentCommand() =
        EditCommand(currentDesignSnapshot(), currentSnapshotMode(), currentModeAdjustmentSnapshot())

    override fun onUndoClicked() = applyHistory(history.popUndo { currentCommand() })

    override fun onRedoClicked() = applyHistory(history.popRedo { currentCommand() })

    private fun applyHistory(command: EditCommand?) {
        command ?: return
        // The bitmap is transient and identical across a property-only change, so carry the live one
        // over rather than reloading it from disk.
        val restored = command.oldDesign?.copy(bitmap = _uiState.value.design?.bitmap)
        val effectsChanged = restored?.isSketch != _uiState.value.design?.isSketch ||
            restored?.isSubjectIsolated != _uiState.value.design?.isSubjectIsolated
        dispatch(EditorIntent.RestoreDesign(restored))
        // Restore the mode adjustment the snapshot was taken alongside, if any — without this, an
        // outside-DESIGN Undo restored an (unchanged) design and left modeAdjustments exactly where
        // they were, a visible no-op; after a Reset it left the real placement nowhere to come back
        // from once the pre-Reset stash was cleared by RestoreDesign just above.
        if (command.oldMode != null && command.oldModeAdjustment != null) {
            dispatch(EditorIntent.SetModeAdjustment(command.oldMode, command.oldModeAdjustment))
        }
        saveProject()
        emitDesignResync(restored)
        updateHistoryCounts()
        // The carried-over bitmap is only valid while the effect flags are unchanged; undoing an
        // effect toggle has to re-render from the source or the pixels contradict the flags.
        if (effectsChanged) recomputeDesignEffects()
    }

    // ── The design ────────────────────────────────────────────────────────────

    override fun onAddLayer(uri: Uri) {
        // A design already sitting on the wall is placement work an artist can lose minutes of —
        // replacing it outright with no confirmation is how "Open" ends up eating a mural in
        // progress. Ask first when there is something to lose; a first import has nothing to
        // confirm away.
        if (_uiState.value.design != null) {
            dispatch(EditorIntent.SetPendingReplaceUri(uri))
        } else {
            applyNewDesign(uri)
        }
    }

    override fun confirmReplaceDesign() {
        val uri = _uiState.value.pendingReplaceUri ?: return
        dispatch(EditorIntent.SetPendingReplaceUri(null))
        applyNewDesign(uri)
    }

    override fun cancelReplaceDesign() {
        dispatch(EditorIntent.SetPendingReplaceUri(null))
    }

    private fun applyNewDesign(uri: Uri) {
        pushHistory()
        viewModelScope.launch(dispatchers.io) {
            // Cap the imported image at a screen-reasonable size. A full 12MP+ photo is ~48MB as ARGB;
            // decoding/copying/PNG-encoding it (then rendering it as a texture every frame) is what
            // made the first layer take seconds to appear and the canvas lag. 2048px is ample here.
            val bitmap = ImageUtils.loadBitmapAsync(context, uri, maxDimension = 2048)
            val projectId = _uiState.value.projectId
            if (bitmap != null && projectId != null) {
                val filename = "design_${UUID.randomUUID()}.png"
                val path = projectRepository.saveArtifact(projectId, filename, ImageUtils.bitmapToByteArray(bitmap))
                val localUri = "file://$path".toUri()

                val metrics = context.resources.displayMetrics
                val screenW = metrics.widthPixels.toFloat()
                val screenH = metrics.heightPixels.toFloat()
                // Fit the imported image to the screen so it lands somewhere usable.
                val initialScale = minOf(screenW * 0.9f / bitmap.width, screenH * 0.9f / bitmap.height, 1.0f)

                // Replaces whatever was there: there is exactly one design, and importing is how
                // the artist chooses it.
                val design = Layer(
                    id = UUID.randomUUID().toString(),
                    name = "Design",
                    uri = localUri,
                    bitmap = bitmap,
                    isVisible = true,
                    scale = initialScale
                )

                withContext(dispatchers.main) {
                    // A new import starts with no effects, so it is its own source.
                    designSourceBitmap = bitmap
                    dispatch(EditorIntent.SetDesign(design))
                    opEmitter.emit(Op.DesignReplace(design))
                    saveProject()
                }
            } else {
                withContext(dispatchers.main) {
                    Toast.makeText(context, "Invalid image format or missing project", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ── Design effects (Outline, subject isolation) ───────────────────────────

    /**
     * Renders [source] through whichever effects [design] has enabled.
     *
     * Isolation runs first and Outline second, because that is the order the results compose: cut
     * the subject out, then draw the lines of what is left. Reversing it would sketch the
     * background and then throw away the very edges that made it worth sketching.
     *
     * Each stage falls back to its input on failure, so a segmenter that can't find a subject or an
     * OpenCV pass that throws costs the user that one effect, not their image.
     */
    /** @return the resulting bitmap, plus a user-facing message if a REQUESTED stage fell back to
     * its input rather than actually applying (null if every requested stage succeeded, or none
     * were requested). */
    private suspend fun applyDesignEffects(source: Bitmap, design: Layer): Pair<Bitmap, String?> {
        var out = source
        var failure: String? = null
        if (design.isSubjectIsolated) {
            val isolated = subjectIsolator.isolate(out).getOrNull()?.isolatedBitmap
            if (isolated != null) out = isolated
            else failure = "Couldn't isolate a subject in this image."
        }
        if (design.isSketch) {
            val sketched = SketchProcessor.sketchEffect(out)
            if (sketched != null) out = sketched
            else failure = "Couldn't generate an outline for this image."
        }
        return out to failure
    }

    /**
     * Re-derives the shown bitmap after an effect toggle, always from [designSourceBitmap] rather
     * than from what is currently displayed — that is what makes the toggles reversible.
     */
    private fun recomputeDesignEffects() {
        val source = designSourceBitmap ?: return
        designEffectJob?.cancel()
        designEffectJob = viewModelScope.launch(dispatchers.default) {
            val design = _uiState.value.design ?: return@launch
            val (rendered, failureMessage) = applyDesignEffects(source, design)
            withContext(dispatchers.main) {
                updateDesign { it.copy(bitmap = rendered) }
                if (failureMessage != null) {
                    _uiState.update { it.copy(effectFailureMessage = failureMessage) }
                }
                saveProject()
                // Guests are shown pixels, not a pipeline, so ship the result rather than the flag.
                opEmitter.emit(Op.DesignBitmapReplace(ImageUtils.bitmapToByteArray(rendered)))
            }
        }
    }

    fun onEffectFailureMessageShown() {
        _uiState.update { it.copy(effectFailureMessage = null) }
    }

    /** Outline: turn the image into a sketch that is actually traceable. */
    override fun onToggleOutline() {
        if (_uiState.value.design == null) return
        pushHistory()
        updateDesign { it.copy(isSketch = !it.isSketch) }
        recomputeDesignEffects()
    }

    /** Subject isolation: drop everything the segmenter does not read as the subject. */
    override fun onToggleSubjectIsolation() {
        if (_uiState.value.design == null) return
        pushHistory()
        updateDesign { it.copy(isSubjectIsolated = !it.isSubjectIsolated) }
        recomputeDesignEffects()
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
                withContext(dispatchers.main) {
                    dispatch(EditorIntent.SetLoading(false))
                    _uiState.update { it.copy(effectFailureMessage = "Couldn't load that image.") }
                }
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
                val updatedDesign = _uiState.value.design?.toOverlayLayer()
                val modeAdjustments = _uiState.value.modeAdjustments.mapKeys { it.key.name }

                // Paths derive from the (immutable) project id.
                val projectId = currentProject?.id ?: GraffitiProject(name = name ?: "New Project").id

                val manifestToSave: GraffitiProject
                if (currentProject == null) {
                    manifestToSave = GraffitiProject(
                        id = projectId,
                        name = name ?: "New Project",
                        design = updatedDesign,
                        modeAdjustments = modeAdjustments,
                    )
                    projectRepository.createProject(manifestToSave)
                } else {
                    // Atomic read-modify-write: a concurrent AR wall-feature-map save merges into the SAME
                    // currentProject, so writing a full stale copy here would drop its wall map (and vice
                    // versa). The transform only touches the editor-owned fields.
                    //
                    // Guarded on id, matching scheduleThumbnailUpdate below: this launches on the IO
                    // dispatcher, so by the time it runs the user may already have switched to a
                    // different project, in which case `current` is that new project, not the one
                    // `updatedDesign`/`modeAdjustments` were captured from — writing them anyway would
                    // clobber the new project with the old one's design.
                    projectRepository.updateProject { current ->
                        if (current.id != projectId) current
                        else current.copy(
                            name = name ?: current.name,
                            design = updatedDesign,
                            modeAdjustments = modeAdjustments,
                            lastModified = System.currentTimeMillis(),
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
        // Snapshot the design NOW, not after the debounce delay below. This is called again on
        // every edit (each call cancels the previous job and restarts the 2s timer), so a fresh
        // snapshot here already picks up the latest edit — reading _uiState.value.design after the
        // delay instead bought nothing for that case, but did mean that if the user switched
        // projects inside the 2s window, this composited whatever project happened to be current
        // when the delay elapsed into the id captured above, writing one project's live artwork
        // into a DIFFERENT project's thumbnail file.
        val design = _uiState.value.design?.takeIf { it.isVisible && it.bitmap != null } ?: return
        // Confine the job cancel/assign to the main thread so concurrent saveProject() calls (which
        // run on the multi-threaded IO dispatcher) can't race on thumbnailJob and leak coroutines.
        viewModelScope.launch(dispatchers.main) {
            thumbnailJob?.cancel()
            thumbnailJob = viewModelScope.launch(dispatchers.default) {
                try {
                    kotlinx.coroutines.delay(2000)
                    val metrics = context.resources.displayMetrics
                    val w = metrics.widthPixels.takeIf { it > 0 } ?: 1080
                    val h = metrics.heightPixels.takeIf { it > 0 } ?: 1920
                    val composite = exportManager.composite(design, w, h)
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
     * @param skipLayerComposite When true, the [backgroundBitmap] IS the export — the design is
     *   not drawn on top. Set by the AR path because the GL readback already contains it as the
     *   wall-anchored quad; drawing it again would double-draw.
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
                    // Every gesture and tone control outside DESIGN mode writes to modeAdjustments,
                    // not the layer — omitting it here exported the design at its untouched default
                    // placement regardless of what was on screen in Overlay/Mockup/Trace.
                    val modeAdj = _uiState.value.editorMode
                        .takeIf { it != EditorMode.DESIGN }
                        ?.let { _uiState.value.modeAdjustments[it] }
                    exportManager.composite(
                        _uiState.value.design,
                        metrics.widthPixels.takeIf { it > 0 } ?: 1080,
                        metrics.heightPixels.takeIf { it > 0 } ?: 1920,
                        backgroundBitmap = bgBmp,
                        backgroundColor = android.graphics.Color.TRANSPARENT,
                        modeAdj = modeAdj,
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
     * Composites the design to a PNG in `cacheDir/shared` and returns a FileProvider
     * `content://` Uri suitable for `ACTION_SEND` — the two-app hand-off back to the design app.
     * Returns null if there's nothing to share. The host fires the share intent; the Uri authority
     * is `${applicationId}.fileprovider`, declared in the manifest.
     */
    suspend fun exportForShare(): Uri? = withContext(dispatchers.default) {
        val design = _uiState.value.design ?: return@withContext null
        val metrics = context.resources.displayMetrics
        val composite = exportManager.composite(
            design,
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

    /**
     * Flips the in-memory placement immediately (so the rail docks on the other side without
     * waiting on IO) and persists the new value to [SettingsRepository] so it survives a restart.
     * Previously this only dispatched the reducer intent — a purely in-memory `EditorUiState` flip
     * with no write to DataStore, even though a working persisted `isRightHanded` flow already
     * existed on [SettingsRepository] and just had no caller.
     */
    fun toggleHandedness() {
        dispatch(EditorIntent.ToggleHandedness)
        val isRightHanded = _uiState.value.isRightHanded
        viewModelScope.launch(dispatchers.io) {
            settingsRepository.setRightHanded(isRightHanded)
        }
    }
    fun toggleDiagOverlay() {
        dispatch(EditorIntent.ToggleDiagOverlay)
        val on = _uiState.value.showDiagOverlay
        viewModelScope.launch(dispatchers.io) { settingsRepository.setShowDiagOverlay(on) }
    }

    fun toggleFeaturePoints() {
        dispatch(EditorIntent.ToggleFeaturePoints)
        val on = _uiState.value.showFeaturePoints
        viewModelScope.launch(dispatchers.io) { settingsRepository.setShowFeaturePoints(on) }
    }

    fun togglePlaneGrids() {
        dispatch(EditorIntent.TogglePlaneGrids)
        val on = _uiState.value.showPlaneGrids
        viewModelScope.launch(dispatchers.io) { settingsRepository.setShowPlaneGrids(on) }
    }

    fun togglePoints() {
        dispatch(EditorIntent.TogglePoints)
        val on = _uiState.value.showPoints
        viewModelScope.launch(dispatchers.io) { settingsRepository.setShowPoints(on) }
    }

    // ── Placement ─────────────────────────────────────────────────────────────

    fun setAnchorExtent(halfW: Float, halfH: Float) {
        anchorHalfExtentMeters = Pair(halfW, halfH)
    }

    private fun fitDesignToAnchor(halfW: Float, halfH: Float) {
        val bmp = _uiState.value.design?.bitmap ?: return
        // QUAD_HALF_EXTENT = 5.0f (matches OverlayRenderer.QUAD_HALF_EXTENT)
        // The composite canvas is 2048×2048. Scale to fill 80% of the anchor extent.
        val scaleW = halfW * 0.8f * 2048f / (bmp.width * 5.0f)
        val scaleH = halfH * 0.8f * 2048f / (bmp.height * 5.0f)
        val scale = minOf(scaleW, scaleH).coerceIn(0.05f, 20f)
        updateDesign { it.copy(scale = scale, offset = Offset.Zero, rotationX = 0f, rotationY = 0f, rotationZ = 0f) }
    }

    override fun onMagicClicked() {
        pushHistory()
        clearTransformStash()
        val extent = anchorHalfExtentMeters
        if (extent != null) {
            fitDesignToAnchor(extent.first, extent.second)
        } else {
            updateDesign { it.copy(brightness = 0.1f, contrast = 1.2f, saturation = 1.1f) }
        }
        saveProject()
    }

    override fun onAdjustClicked() = dispatch(EditorIntent.ToggleAdjustPanel)
    fun onBalanceClicked() = dispatch(EditorIntent.ToggleColorPanel)

    override fun onDismissPanel() = dispatch(EditorIntent.DismissPanel)

    /**
     * The Reset button. Toggles between "placement flattened to identity" and "placement exactly as
     * it was before the first press" — see [EditorIntent.ToggleTransformReset]. Recorded in history
     * like any other placement change, so undo also backs it out.
     */
    override fun onResetClicked() {
        pushHistory()
        dispatch(EditorIntent.ToggleTransformReset)
        saveProject()
        _uiState.value.design?.let { opEmitter.emit(Op.DesignTransform(it.encodeTransform())) }
    }

    fun onTransformGesture(pan: Offset, zoom: Float, rotationDelta: Float) {
        // Rotation goes to the axis the double-tap cycle selected — X/Y tilt the design about its
        // width/height, Z spins it in-plane.
        val axis = _uiState.value.activeRotationAxis
        // Repositioning by hand voids any pending Reset restore: "put it back" would put it
        // somewhere the user has deliberately moved away from.
        clearTransformStash()
        updateDesign { layer ->
            val rx = if (axis == RotationAxis.X) layer.rotationX + rotationDelta else layer.rotationX
            val ry = if (axis == RotationAxis.Y) layer.rotationY + rotationDelta else layer.rotationY
            val rz = if (axis == RotationAxis.Z) layer.rotationZ + rotationDelta else layer.rotationZ
            // Clamped to the same [0.1, 10] range ApplyModeTransformGesture already enforces for
            // every non-Design mode's pinch — the same gesture source had two different contracts.
            layer.copy(scale = (layer.scale * zoom).coerceIn(0.1f, 10f), offset = layer.offset + pan, rotationX = rx, rotationY = ry, rotationZ = rz)
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
        val mode = _uiState.value.editorMode
        if (mode == EditorMode.DESIGN) {
            // The editor stores transform as scale/offset/rotationX/Y/Z rather than a Matrix, so we
            // encode them in the first 6 slots of a 16-float list (slots 6-15 are zeros).
            // applySpectatorOp must decode using the same convention.
            val layer = _uiState.value.design ?: return
            opEmitter.emit(Op.DesignTransform(layer.encodeTransform()))
        } else {
            // Outside DESIGN, a transform gesture writes to modeAdjustments, not the design layer —
            // the design's own transform is untouched, so emitting DesignTransform here always sent
            // an unchanging identity and the guest's copy of the artwork never moved. Send what
            // actually changed instead.
            val adjustment = _uiState.value.modeAdjustments[mode] ?: return
            opEmitter.emit(Op.ModeTransform(mode.name, adjustment))
        }
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

    override fun onCycleRotationAxis() = dispatch(EditorIntent.CycleRotationAxis)

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
        val bitmap = _uiState.value.design?.bitmap ?: return
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
    override fun onLockedFeedbackShown() = dispatch(EditorIntent.LockedFeedbackShown)

    // ── Co-op ─────────────────────────────────────────────────────────────────

    /** Re-pushes the design's props and transform to guests after an undo/redo. */
    private fun emitDesignResync(design: Layer?) {
        design ?: return
        opEmitter.emit(Op.DesignProps(design.toLayerProps()))
        opEmitter.emit(Op.DesignTransform(design.encodeTransform()))
    }

    /** Emits a co-op props change for the design, if there is one. */
    private fun emitActiveLayerProps() {
        _uiState.value.design?.let { opEmitter.emit(Op.DesignProps(it.toLayerProps())) }
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
            is Op.DesignReplace -> dispatch(EditorIntent.RestoreDesign(op.design))
            is Op.DesignTransform -> {
                // The host encodes transform as [scale, offsetX, offsetY, rotX, rotY, rotZ, 0...0].
                if (op.matrix.size >= 6) {
                    dispatch(EditorIntent.SetDesignTransform(
                        scale = op.matrix[0],
                        offset = Offset(op.matrix[1], op.matrix[2]),
                        rx = op.matrix[3], ry = op.matrix[4], rz = op.matrix[5],
                    ))
                }
            }
            is Op.ModeTransform -> {
                runCatching { EditorMode.valueOf(op.mode) }.getOrNull()?.let { mode ->
                    dispatch(EditorIntent.SetModeAdjustment(mode, op.adjustment))
                }
            }
            is Op.DesignProps -> dispatch(EditorIntent.SetDesignProps(op.props))
            is Op.DesignBitmapReplace -> {
                if (_uiState.value.design == null) return
                viewModelScope.launch(dispatchers.default) {
                    // Cap the decoded bitmap at 2x the longest screen edge — plenty for an image
                    // that rasterises to a screen quad, and it stops a peer accidentally shipping a
                    // giant PNG from OOMing the guest.
                    val metrics = context.resources.displayMetrics
                    val maxDim = maxOf(metrics.widthPixels, metrics.heightPixels) * 2
                    val decoded = com.hereliesaz.graffitixr.common.util.decodeBoundedBitmap(op.png, maxDim) ?: run {
                        android.util.Log.w(
                            "EditorViewModel",
                            "DesignBitmapReplace: decode returned null (bytes=${op.png.size})"
                        )
                        return@launch
                    }
                    withContext(dispatchers.main) {
                        _uiState.update { s -> s.copy(design = s.design?.copy(bitmap = decoded)) }
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

    private fun updateDesign(transform: (Layer) -> Layer) {
        _uiState.update { state -> state.copy(design = state.design?.let(transform)) }
    }

    /**
     * Voids a pending Reset restore. The reducer does this for the intents it owns; this is for the
     * paths that mutate the design directly (gesture transform, magic align).
     */
    private fun clearTransformStash() {
        if (_uiState.value.transformStash != null) _uiState.update { it.copy(transformStash = null) }
    }

    /**
     * MVI dispatch: apply a state-only [EditorIntent] through the pure [EditorReducer]. Side
     * effects (history, persistence, co-op op emission) are orchestrated by the caller around
     * this call — the reducer itself stays pure.
     */
    private fun dispatch(intent: EditorIntent) {
        _uiState.update { EditorReducer.reduce(it, intent) }
    }

}
