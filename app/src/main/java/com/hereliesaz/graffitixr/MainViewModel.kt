// FILE: app/src/main/java/com/hereliesaz/graffitixr/MainViewModel.kt
package com.hereliesaz.graffitixr

import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import java.nio.ByteBuffer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.common.model.CaptureStep
import com.hereliesaz.graffitixr.data.ProjectManager
import com.hereliesaz.sphereslam.camera.CaptureRotation
import com.hereliesaz.graffitixr.feature.ar.anchor.MetricDoodleBuilder
import com.hereliesaz.sphereslam.reloc.MetricMarks
import com.hereliesaz.graffitixr.feature.ar.anchor.StandaloneDoodleBuilder
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class MainUiState(
    val isTouchLocked: Boolean = false,
    val isCapturingTarget: Boolean = false,
    // Whether the current project already has a saved target doodle. null = not yet resolved
    // (project still loading) so AR entry can wait instead of racing; true/false drives whether AR
    // entry auto-selects the Target button (no target yet) or drops straight into layer editing.
    val hasExistingTarget: Boolean? = null,
    val hasArCoreTarget: Boolean? = null,
    val hasSphereSlamTarget: Boolean? = null,
    // Compass/attitude saved with the ARCore target (TYPE_ROTATION_VECTOR quaternion [x,y,z,w],
    // body->ENU) and its magnetometer accuracy, for the coarse "which way was it?" search hint.
    // Null when the project has no ARCore target or it was captured without a usable sensor.
    val savedTargetQuaternion: List<Float>? = null,
    val savedTargetMagneticAccuracy: Int = -1,
    // Identity of the loaded project, so the search-status timer restarts when it changes.
    val loadedProjectId: String? = null,
    // True once the user has successfully created a target in this app session.
    // Prevents auto-starting target capture on AR re-entry within the same process.
    val targetCapturedThisSession: Boolean = false,
    val captureStep: CaptureStep = CaptureStep.NONE,
    // Phase 4: True while the user is in "tap your painted marks" mode.
    val isWaitingForTap: Boolean = false,
    // True when the current capture was initiated via the tap-to-target path (Phase 4).
    val captureOriginatedFromTap: Boolean = false,
    // True from the moment the artist confirms a target capture until the async
    // doodle-build work (awaitAnchorTransform, ORB/SuperPoint) finishes, success or failure.
    // resetCaptureUi() unmounts TargetCreationUi (and its own isLoading spinner) immediately on
    // confirm, well before this work is done — up to ~2s of awaitAnchorTransform's timeout alone,
    // plus a full feature build — so without a SEPARATE flag surviving that unmount, the artist
    // saw a plain AR feed with no indication anything was happening.
    val isConfirmingTarget: Boolean = false,
    // Overlay ▸ Gyro: hold the drawn design still against tripod vibration (see
    // OverlayGyroStabilizer). Overlay-only; MainScreen turns it off on leaving Overlay.
    val isOverlayGyroActive: Boolean = false,
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val slamManager: SlamManager,
    private val projectManager: ProjectManager,
    private val settingsRepository: SettingsRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    // Tracks the previously-seen project id so the currentProject collector below can tell "the same
    // project reloaded" (e.g. the loadProject() call right after a target capture saves) apart from
    // "a different project was opened" — only the latter should reset targetCapturedThisSession.
    private var lastSeenProjectId: String? = null

    /**
     * A standalone target replacement that is committed but not yet accepted by native KPM
     * registration: [previous] is the project as it stood before (its page 0 and atlas files are kept
     * until the verdict). [onStandaloneReferenceRegistered] deletes them; [onStandaloneReferenceRejected]
     * restores them. In memory only: an app kill in the seconds between confirm and registration keeps
     * the candidate and leaves the previous files orphaned.
     */
    private class PendingStandaloneReplacement(
        val projectId: String,
        val candidateUri: android.net.Uri,
        val previous: com.hereliesaz.graffitixr.common.model.GraffitiProject,
    )

    private val replacementLock = Any()
    private var pendingStandaloneReplacement: PendingStandaloneReplacement? = null // guarded by replacementLock

    init {
        // Track whether the loaded project already has a saved target so AR entry knows whether to
        // pre-select the Target button. Updated on confirm via the repository's project re-load.
        viewModelScope.launch {
            projectRepository.currentProject.collect { project ->
                // Only resolve once a real project arrives; leave it null while loading so the UI
                // doesn't briefly see "no target" and auto-start target capture by mistake.
                if (project != null) {
                    val hasArCoreTarget = project.doodle != null
                    val hasSphereSlamTarget =
                        project.sphereSlamReferenceUri != null && project.sphereSlamDoodle != null
                    _uiState.update {
                        it.copy(
                            hasExistingTarget = hasArCoreTarget || hasSphereSlamTarget,
                            hasArCoreTarget = hasArCoreTarget,
                            hasSphereSlamTarget = hasSphereSlamTarget,
                            savedTargetQuaternion = if (hasArCoreTarget) {
                                project.captureEnvironment?.attitude?.rotationVector
                                    ?.takeIf { q -> q.size == 4 }
                            } else null,
                            savedTargetMagneticAccuracy =
                                project.captureEnvironment?.attitude?.magneticAccuracy ?: -1,
                            loadedProjectId = project.id.toString(),
                        )
                    }
                    // targetCapturedThisSession is meant to read "has THIS project (in this session)
                    // had a target captured", not "has any project, ever, this process" — otherwise
                    // capturing a target in one project silently suppressed the pre-selection prompt
                    // for every other project opened later. Reset it whenever the loaded project
                    // actually changes. Guarded on a non-null previous id so it doesn't fire on the
                    // very first project load, and compares ids (not object identity) so the reload
                    // onConfirmTargetCreation triggers right after setting the flag true — same
                    // project, same id — does not immediately clobber it.
                    val previousId = lastSeenProjectId
                    if (previousId != null && previousId != project.id) {
                        _uiState.update { it.copy(targetCapturedThisSession = false) }
                    }
                    lastSeenProjectId = project.id
                }
            }
        }
    }

    /** Settings → Doodle, for the target captures built here. */
    private val doodleFeatures: StateFlow<com.hereliesaz.graffitixr.common.model.DoodleFeatures> =
        settingsRepository.doodleFeatures.stateIn(
            viewModelScope, SharingStarted.Eagerly, com.hereliesaz.graffitixr.common.model.DoodleFeatures.DEFAULT,
        )

    val completedTutorials: StateFlow<Set<String>> = settingsRepository.completedTutorials
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /**
     * True once [completedTutorials] has been read from the store. The StateFlow's `emptySet()` seed
     * is indistinguishable from "nothing completed yet", so a one-shot overlay must wait for this
     * before deciding it has not been shown.
     */
    val completedTutorialsLoaded: StateFlow<Boolean> = settingsRepository.completedTutorials
        .map { true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun markTutorialCompletePersistent(key: String) {
        viewModelScope.launch { settingsRepository.markTutorialComplete(key) }
    }

    fun setTouchLocked(locked: Boolean) {
        _uiState.update { it.copy(isTouchLocked = locked) }
    }

    fun setOverlayGyroActive(active: Boolean) {
        _uiState.update { it.copy(isOverlayGyroActive = active) }
    }

    /**
     * The Overlay ▸ Gyro rail toggle. Gyro stabilises the live camera view, so it can only switch
     * on while that view is showing; otherwise the toggle stays (or goes) off. Arming it without
     * the camera used to leave the button lit with no effect, then silently start stabilising the
     * moment the camera permission arrived — against a reference the artist never chose.
     */
    fun toggleOverlayGyro(cameraShowing: Boolean) {
        _uiState.update {
            it.copy(isOverlayGyroActive = cameraShowing && !it.isOverlayGyroActive)
        }
    }

    /** Releases Gyro whenever the Overlay camera view is not showing; never arms it. */
    fun onOverlayCameraShowingChanged(cameraShowing: Boolean) {
        if (!cameraShowing) _uiState.update { it.copy(isOverlayGyroActive = false) }
    }

    /**
     * Saves a clean-wall photo (pass B's reference) into the current project, replacing any earlier
     * one. The native side already has it; this makes it survive a restart.
     */
    fun saveCleanWallPhoto(bitmap: android.graphics.Bitmap, onResult: (saved: Boolean) -> Unit = {}) {
        val projectId = projectRepository.currentProject.value?.id
        if (projectId == null) { onResult(false); return }
        viewModelScope.launch(Dispatchers.IO) {
            val path = runCatching {
                projectRepository.saveArtifact(
                    projectId, "clean_wall.png",
                    com.hereliesaz.graffitixr.common.util.ImageUtils.bitmapToByteArray(bitmap),
                )
            }.getOrNull()
            var committed = false
            if (path != null) {
                runCatching {
                    projectRepository.updateProject { current ->
                        if (current.id != projectId) current
                        else {
                            committed = true
                            current.copy(cleanWallUri = android.net.Uri.parse("file://$path"))
                        }
                    }
                }
            }
            withContext(Dispatchers.Main) { onResult(path != null && committed) }
        }
    }

    fun startTargetCapture() {
        _uiState.update {
            it.copy(
                isCapturingTarget = true,
                // Phase 4: Skip the auto-capture step — user taps their painted marks instead.
                captureStep = CaptureStep.NONE,
                isWaitingForTap = true,
                captureOriginatedFromTap = true
            )
        }
    }

    fun confirmTapCapture() {
        _uiState.update {
            it.copy(
                isWaitingForTap = false,
                captureStep = CaptureStep.REVIEW
            )
        }
    }

    fun cancelTapMode() {
        resetCaptureUi()
    }

    fun setCaptureStep(step: CaptureStep) {
        _uiState.update { it.copy(captureStep = step) }
    }

    fun onRetakeCapture() {
        val fromTap = _uiState.value.captureOriginatedFromTap
        _uiState.update {
            it.copy(
                captureStep = if (fromTap) CaptureStep.NONE else CaptureStep.CAPTURE,
                isWaitingForTap = fromTap
            )
        }
    }

    /**
     * Confirm the shared TargetCreationUi capture while SphereSLAM standalone owns pose tracking.
     *
     * The artist-facing workflow is intentionally identical to ARCore: tap target, review detected
     * marks, erase any that should not anchor the mural, confirm. Only the geometry stage differs:
     * standalone maps the reviewed features into SphereSLAM's canonical centered page frame.
     */
    fun onConfirmStandaloneTargetCreation(
        bitmap: Bitmap?,
        selectionMask: Bitmap?,
        placement: com.hereliesaz.graffitixr.feature.ar.StandaloneDoodlePlacement?,
    ) {
        if (bitmap == null) {
            resetCaptureUi()
            Toast.makeText(
                context,
                "Capture incomplete. Try creating the Doodle again.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        if (placement == null) {
            Toast.makeText(
                context,
                "SphereSLAM map is not ready to anchor this Doodle yet. Keep the camera on the wall and try again.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        resetCaptureUi()
        _uiState.update { it.copy(isConfirmingTarget = true) }
        viewModelScope.launch(Dispatchers.IO) {
            var candidateUri: android.net.Uri? = null
            try {
                val currentProject = projectRepository.currentProject.value
                if (currentProject == null) {
                    MetricDoodleBuilder.recordPreconditionFailure(
                        "no project open to save the standalone target into",
                    )
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "No project open — a Doodle is saved into a project. Open or create one first.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    return@launch
                }

                // Doodle geometry is authored in the already-running photosphere's units.
                // Without metric range those units are normalized; the explicit map transform still
                // places the page correctly in the base SphereSLAM frame.
                val fp = StandaloneDoodleBuilder.build(
                    slam = slamManager,
                    bitmap = bitmap,
                    referenceWidthMeters = placement.referenceWidthUnits,
                    mask = selectionMask,
                    features = doodleFeatures.value,
                )
                if (fp == null) {
                    MetricDoodleBuilder.recordPreconditionFailure(
                        "standalone target produced too few selected SphereSLAM doodle features",
                    )
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "Doodle lacks enough selected visual detail. Keep more distinctive wall marks.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    return@launch
                }

                val patch = grayPatchBytes(bitmap)
                val doodle = fp.copy(patchData = patch)

                val oldReference = currentProject.sphereSlamReferenceUri
                val oldAtlas = currentProject.sphereSlamAtlasPages
                candidateUri =
                    projectManager.saveSphereSlamReference(context, currentProject.id, bitmap)
                val baseTargetUris =
                    projectRepository.currentProject.value?.targetImageUris
                        ?: currentProject.targetImageUris
                val targetUris =
                    projectManager.appendTargetImage(
                        context,
                        currentProject.id,
                        baseTargetUris,
                        bitmap,
                    )

                var committed = false
                var previous: com.hereliesaz.graffitixr.common.model.GraffitiProject? = null
                projectRepository.updateProject { current ->
                    if (
                        current.id != currentProject.id ||
                        current.sphereSlamReferenceUri != oldReference
                    ) {
                        current
                    } else {
                        committed = true
                        previous = current
                        current.copy(
                            sphereSlamReferenceUri = candidateUri,
                            sphereSlamReferenceWidthMeters = placement.referenceWidthUnits,
                            sphereSlamReferencePhysicallyMetric = placement.physicallyMetric,
                            sphereSlamAnchorGeneration = current.sphereSlamAnchorGeneration + 1L,
                            sphereSlamAnchorFrameVersion =
                                com.hereliesaz.graffitixr.common.model.SPHERE_SLAM_DOODLE_FRAME_VERSION,
                            sphereSlamPlacementAnchorGeneration = 0L,
                            sphereSlamDoodle = doodle,
                            // A new target may be another wall: its normal is learned afresh.
                            doodleWallNormalEnu = emptyList(),
                            sphereSlamDoodleFrameVersion =
                                com.hereliesaz.graffitixr.common.model.SPHERE_SLAM_DOODLE_FRAME_VERSION,
                            sphereSlamMapFromDoodle = placement.mapFromFingerprint.toList(),
                            sphereSlamWallFeatureMap = null,
                            sphereSlamWallFeatureMapFrameVersion =
                                com.hereliesaz.graffitixr.common.model.SPHERE_SLAM_DOODLE_FRAME_VERSION,
                            // Base photosphere history belongs to SphereSLAM and survives precision
                            // doodle creation/recapture. Only the doodle-relative KPM atlas
                            // is replaced here.
                            sphereSlamAtlasPages = emptyList(),
                            paintMarks = null,
                            paintGrid = null,
                            targetImageUris = targetUris,
                        )
                    }
                }

                if (!committed) {
                    projectManager.deleteTargetImages(targetUris - baseTargetUris.toSet())
                    projectManager.deleteSphereSlamReference(
                        context,
                        currentProject.id,
                        candidateUri,
                    )
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "The Doodle changed while this one was saving — not saved. Try again.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    return@launch
                }
                // Only a committed target's patch reaches the native wall matcher.
                slamManager.setWallPatchBytes(patch, PATCH_SIZE)
                slamManager.setWallNormalEnu(null) // a new target: its wall normal is learned afresh
                projectManager.deleteTargetImages(baseTargetUris - targetUris.toSet())

                // The previous target stays on disk until native KPM accepts the candidate: a candidate
                // that builds a doodle can still be too weak to register, and then the previous
                // target is restored (onStandaloneReferenceRejected). Replacing a candidate that is
                // itself still unvalidated keeps the original rollback and drops that candidate.
                val keepOldForRollback = synchronized(replacementLock) {
                    val superseded = pendingStandaloneReplacement?.takeIf {
                        it.projectId == currentProject.id && it.candidateUri == oldReference
                    }
                    val rollback = (superseded?.previous ?: previous)
                        ?.takeIf { it.sphereSlamReferenceUri != null }
                    pendingStandaloneReplacement =
                        rollback?.let { PendingStandaloneReplacement(currentProject.id, candidateUri!!, it) }
                    // The old page is the rollback itself only when it was not an unvalidated candidate.
                    rollback != null && superseded == null
                }
                if (!keepOldForRollback) {
                    projectManager.deleteSphereSlamReference(context, currentProject.id, oldReference)
                    oldAtlas.forEach { page ->
                        projectManager.deleteSphereSlamAtlasPage(context, currentProject.id, page.referenceUri)
                    }
                }

                projectRepository.loadProject(currentProject.id)
                _uiState.update { it.copy(targetCapturedThisSession = true) }
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Doodle Saved & Locked", Toast.LENGTH_SHORT).show()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                candidateUri?.let {
                    runCatching {
                        projectManager.deleteSphereSlamReference(
                            context,
                            projectRepository.currentProject.value?.id ?: return@runCatching,
                            it,
                        )
                    }
                }
                MetricDoodleBuilder.recordPreconditionFailure(
                    "standalone target save failed: ${e.message ?: e.javaClass.simpleName}",
                )
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "Couldn't save the SphereSLAM target. Try again.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } finally {
                // A build that failed or was abandoned leaves the replacement gate up; restore the old pair.
                slamManager.abortDoodleReplacement()
                _uiState.update { it.copy(isConfirmingTarget = false) }
            }
        }
    }

    /**
     * Native KPM registered the standalone page persisted at [uri]. When it is a pending replacement,
     * the previous target's files are no longer needed and are deleted.
     */
    fun onStandaloneReferenceRegistered(uri: android.net.Uri) {
        val accepted = synchronized(replacementLock) {
            pendingStandaloneReplacement?.takeIf { it.candidateUri == uri }
                ?.also { pendingStandaloneReplacement = null }
        } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                projectManager.deleteSphereSlamReference(
                    context, accepted.projectId, accepted.previous.sphereSlamReferenceUri,
                )
                accepted.previous.sphereSlamAtlasPages.forEach { page ->
                    projectManager.deleteSphereSlamAtlasPage(context, accepted.projectId, page.referenceUri)
                }
            }
        }
    }

    /**
     * Native KPM rejected (or could not read) the standalone page persisted at [uri]. When it is a
     * pending replacement, the previous target is restored in the project — the overlay then swaps
     * back to it — and the candidate's file is deleted.
     *
     * @return true when [uri] was a pending replacement (the previous target is being restored).
     */
    fun onStandaloneReferenceRejected(uri: android.net.Uri): Boolean {
        val rejected = synchronized(replacementLock) {
            pendingStandaloneReplacement?.takeIf { it.candidateUri == uri }
                ?.also { pendingStandaloneReplacement = null }
        } ?: return false
        viewModelScope.launch(Dispatchers.IO) {
            val p = rejected.previous
            var restored = false
            projectRepository.updateProject { current ->
                if (current.id != rejected.projectId || current.sphereSlamReferenceUri != uri) {
                    current
                } else {
                    restored = true
                    // A new generation: everything keyed on the anchor re-solves for the restored page.
                    // The previous placement state is carried over (placed stays placed).
                    val generation = current.sphereSlamAnchorGeneration + 1L
                    current.copy(
                        sphereSlamReferenceUri = p.sphereSlamReferenceUri,
                        sphereSlamReferenceWidthMeters = p.sphereSlamReferenceWidthMeters,
                        sphereSlamReferencePhysicallyMetric = p.sphereSlamReferencePhysicallyMetric,
                        sphereSlamAnchorGeneration = generation,
                        sphereSlamAnchorFrameVersion = p.sphereSlamAnchorFrameVersion,
                        sphereSlamPlacementAnchorGeneration =
                            if (p.sphereSlamPlacementAnchorGeneration == p.sphereSlamAnchorGeneration) {
                                generation
                            } else {
                                p.sphereSlamPlacementAnchorGeneration
                            },
                        sphereSlamDoodle = p.sphereSlamDoodle,
                        sphereSlamDoodleFrameVersion = p.sphereSlamDoodleFrameVersion,
                        sphereSlamMapFromDoodle = p.sphereSlamMapFromDoodle,
                        sphereSlamWallFeatureMap = p.sphereSlamWallFeatureMap,
                        sphereSlamWallFeatureMapFrameVersion = p.sphereSlamWallFeatureMapFrameVersion,
                        sphereSlamAtlasPages = p.sphereSlamAtlasPages,
                        paintMarks = p.paintMarks,
                        paintGrid = p.paintGrid,
                    )
                }
            }
            if (!restored) return@launch
            runCatching { projectManager.deleteSphereSlamReference(context, rejected.projectId, uri) }
            projectRepository.loadProject(rejected.projectId)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    "That target has too little trackable detail — kept the previous one.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        return true
    }

    fun onConfirmTargetCreation(
        bitmap: Bitmap? = null,
        selectionMask: Bitmap? = null,
        depthBuffer: ByteBuffer? = null,
        depthW: Int = 0,
        depthH: Int = 0,
        depthStride: Int = 0,
        intrinsics: FloatArray? = null,
        viewMatrix: FloatArray? = null,
        wallPlane: FloatArray? = null,
        /**
         * `rotationNeeded` for this capture — the angle already applied to [bitmap] and
         * [intrinsics]. Routed to the view matrix too (IMPLEMENTATION.md Phase 0, convention B).
         */
        rotationDeg: Int = 0,
        /** How and where the device was held at capture; persisted onto the project. */
        captureEnvironment: com.hereliesaz.graffitixr.common.model.CaptureEnvironment? = null,
    ) {
        if (bitmap == null || intrinsics == null || viewMatrix == null) {
            // Was a bare reset: the artist confirmed a target and the capture simply vanished with no
            // message, indistinguishable from the app ignoring the tap. Name it — these are ARCore
            // frame values that should always be present by confirm time, so if this fires it is a
            // real bug worth reporting rather than something to re-aim around.
            resetCaptureUi()
            Toast.makeText(
                context,
                "Capture incomplete (missing camera pose). Try creating the Doodle again.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val safeIntr = intrinsics
        val safeView = viewMatrix

        if (depthBuffer == null || wallPlane != null) {
            // Build the wall doodle from a SINGLE capture by back-projecting features onto the
            // ARCore wall plane (whose metric pose ARCore already solved), inside the artist's
            // selection. Preferred even when depth exists: it is the only path that records the full
            // co-registration (intrinsics, anchor, display-oriented view, captureAnchorCam), without
            // which a project can neither be drift-corrected nor re-anchored on a return visit. The
            // depth path below is the fallback for a capture with depth but no wall plane.
            handleSingleCapture(bitmap, safeIntr, safeView, wallPlane, rotationDeg, captureEnvironment, selectionMask)
            return
        }
        resetCaptureUi()
        _uiState.update { it.copy(isConfirmingTarget = true) }
        val safeDepth = depthBuffer

        viewModelScope.launch(Dispatchers.IO) { try {
            // Same fix as handleSingleCapture's sibling check just below: a bare `?: return@launch`
            // here discarded the capture with no message on a device with no open project — the
            // artist aimed, tapped, confirmed, and nothing happened.
            val currentProject = projectRepository.currentProject.value
            if (currentProject == null) {
                MetricDoodleBuilder.recordPreconditionFailure("no project open to save the target into")
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "No project open — a Doodle is saved into a project. Open or create one first.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                return@launch
            }

            // The confirmed bitmap AND [intrinsics] were both rotated by rotationDeg into display
            // orientation, but the depth buffer is in the raw sensor frame and generateDoodle
            // indexes it by scaling image pixels, then back-projects with the intrinsics. Undo the
            // SAME recorded rotation on image, mask and intrinsics so all three share the sensor
            // frame. (This used to un-rotate the bitmap by a hardcoded -90 gated on display rotation
            // while passing the display-rotated intrinsics, so cx/cy and fx/fy were swapped against
            // the image and every back-projected point was off — and reverse portrait/landscape
            // got the wrong un-rotation entirely.)
            val unrotateDeg = ((rotationDeg % 360) + 360) % 360
            fun toSensorFrame(b: Bitmap): Bitmap =
                if (unrotateDeg == 0) b else Bitmap.createBitmap(
                    b, 0, 0, b.width, b.height,
                    android.graphics.Matrix().apply { postRotate(-unrotateDeg.toFloat()) }, true,
                )
            val sensorBmp = toSensorFrame(bitmap)
            val sensorMask = selectionMask?.let { toSensorFrame(it) }
            val sensorIntr = if (safeIntr.size >= 4) {
                CaptureRotation.unrotateIntrinsics(
                    safeIntr[0], safeIntr[1], safeIntr[2], safeIntr[3],
                    bitmap.width.toFloat(), bitmap.height.toFloat(), unrotateDeg,
                )
            } else {
                safeIntr // native rejects < 4; nothing to un-rotate
            }

            val fp = slamManager.setWallDoodle(
                sensorBmp, sensorMask, safeDepth,
                depthW, depthH, depthStride,
                sensorIntr, safeView
            )

            if (fp == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Doodle lacks visual detail. Please use a surface with more contrast.", Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            // (Teleological artwork base is registered from the live design composite via
            // ArViewModel.updatePaintingGuide — the design-only overlay, not blended with wall texture.)

            // Canonical patch (the captured marks) for the distortion head — fed live AND persisted on the
            // doodle so the head survives reload. Inert unless its model is bundled.
            val patch = grayPatchBytes(sensorBmp)
            slamManager.setWallPatchBytes(patch, PATCH_SIZE)
            slamManager.setWallNormalEnu(null) // a new target: its wall normal is learned afresh
            // This path produces no capture pose. A stale one from an earlier capture this session
            // would pair these points with the old anchor in PoseFusion.
            slamManager.captureAnchorCam = null

            // Target capture is a writer of the SAME project.json as the editor's design-layer save and
            // AR's wall-feature-map save. A raw whole-object saveProject here (as this used to be) would
            // bypass the repository's atomic transform and could silently clobber whichever of those two
            // ran concurrently (or be clobbered by them) — see docs/AUDIT.md save-race. Route through
            // updateProject { } instead, and only copy the fields target-capture owns onto `current`.
            val baseTargetUris = projectRepository.currentProject.value?.targetImageUris ?: currentProject.targetImageUris
            val updatedTargetUris = projectManager.appendTargetImage(context, currentProject.id, baseTargetUris, bitmap)
            // Guarded on id like the standalone path: this runs on IO well after the tap, and the
            // artist may have opened another project meanwhile — `current` would then be THAT
            // project, and the old code wrote this wall's doodle into it.
            var committed = false
            projectRepository.updateProject { current ->
                if (current.id != currentProject.id) return@updateProject current
                committed = true
                current.copy(
                    doodle = fp.copy(patchData = patch),
                    // New capture = new doodle frame; paint placed in the old one is invalid,
                    // and so is the wall feature map (its points are in the old frame too).
                    paintMarks = null,
                    paintGrid = null,
                    wallFeatureMap = null,
                    // This path records no co-registration. Clear any left by an earlier single
                    // capture: reload would otherwise pair these new points with the old capture's
                    // anchor, intrinsics and view, and relocalize against geometry that doesn't match.
                    doodleIntrinsics = emptyList(),
                    doodleAnchor = emptyList(),
                    doodleViewMatrix = emptyList(),
                    doodleCaptureRotationDeg = -1,
                    // A new target may be another wall: its normal is learned afresh.
                    doodleWallNormalEnu = emptyList(),
                    targetImageUris = updatedTargetUris,
                )
            }
            if (!committed) {
                projectManager.deleteTargetImages(updatedTargetUris - baseTargetUris.toSet())
                notifyProjectChangedDuringSave()
                return@launch
            }
            // The pruned entries are no longer referenced now that the list is committed.
            projectManager.deleteTargetImages(baseTargetUris - updatedTargetUris.toSet())

            projectRepository.loadProject(currentProject.id)
            _uiState.update { it.copy(targetCapturedThisSession = true) }

            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Doodle Saved & Locked", Toast.LENGTH_SHORT).show()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // An IOException from appendTargetImage / the project write (disk full, project dir
            // gone) used to escape this viewModelScope coroutine and take the whole app down.
            onTargetSaveFailed(e)
        } finally {
            // A build that failed or was abandoned leaves the replacement gate up; restore the old pair.
            slamManager.abortDoodleReplacement()
            _uiState.update { it.copy(isConfirmingTarget = false) }
        } }
    }

    /**
     * The canonical distortion-head patch as a raw [PATCH_SIZE]x[PATCH_SIZE] gray byte buffer (row-major
     * luma). Computed in Kotlin so the exact bytes are both fed to native and persisted on the
     * Doodle — keeping the capture-time and reload-time patch identical.
     */
    private fun grayPatchBytes(bitmap: Bitmap, size: Int = PATCH_SIZE): ByteArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, size, size, true)
        val px = IntArray(size * size)
        scaled.getPixels(px, 0, size, 0, 0, size, size)
        if (scaled != bitmap) scaled.recycle()
        val out = ByteArray(size * size)
        for (i in px.indices) {
            val p = px[i]
            val r = (p ushr 16) and 0xFF; val g = (p ushr 8) and 0xFF; val b = p and 0xFF
            out[i] = ((r * 299 + g * 587 + b * 114) / 1000).toByte() // BT.601 luma
        }
        return out
    }

    private fun resetCaptureUi() {
        _uiState.update {
            it.copy(
                isCapturingTarget = false,
                captureStep = CaptureStep.NONE,
                isWaitingForTap = false,
                captureOriginatedFromTap = false
            )
        }
    }

    /**
     * Depth-off, SINGLE-capture target creation. The tap must land on a tracked ARCore wall plane
     * (the plane no longer has to be green — see the hit-test in ArRenderer); ARCore has already
     * solved that plane's metric pose, so we back-project the captured features onto it
     * ([MetricDoodleBuilder.buildSingle]) instead of triangulating a second view. No tracked
     * plane under the tap → refuse and guide the artist to face a wall.
     */
    @OptIn(com.hereliesaz.sphereslam.reloc.ExperimentalSphereSlamRelocApi::class)
    private fun handleSingleCapture(
        bitmap: Bitmap, intr: FloatArray, view: FloatArray, wallPlane: FloatArray?, rotationDeg: Int,
        captureEnvironment: com.hereliesaz.graffitixr.common.model.CaptureEnvironment?,
        selectionMask: Bitmap? = null,
    ) {
        if (wallPlane == null || wallPlane.size < 6) {
            resetCaptureUi()
            // Recorded into the SAME shared field `buildSingle` writes to below, on the OTHER two
            // preconditions here and in ArViewModel's doodle path — this call never reaches
            // buildSingle at all, so without this line a diagnostic report would show whatever the
            // PREVIOUS attempt left behind, or nothing, in place of this refusal. See
            // MetricDoodleBuilder.lastRefusalReason: this Toast was the only record this
            // failure ever had, and a Toast a report can't see is not a record.
            MetricDoodleBuilder.recordPreconditionFailure(
                "no wall plane under the tap — face the wall more squarely, closer, or with more light",
            )
            Toast.makeText(context, notOnGreenWallMessage, Toast.LENGTH_LONG).show()
            return
        }
        resetCaptureUi()
        _uiState.update { it.copy(isConfirmingTarget = true) }
        val planePoint = floatArrayOf(wallPlane[0], wallPlane[1], wallPlane[2])
        val planeNormal = floatArrayOf(wallPlane[3], wallPlane[4], wallPlane[5])
        // Clear any prior marks-centering override; the builder re-publishes it on a successful build.
        slamManager.overlayMarkCenterLocal = null
        viewModelScope.launch(Dispatchers.IO) { try {
            // The anchor is read HERE, not on the caller's thread, and only once one exists.
            //
            // Target creation is what ESTABLISHES the anchor: `setInitialAnchorFromCapture` raises a
            // flag and the renderer acts on it a frame later. Reading `getAnchorTransform()`
            // synchronously at the call site — as this did — therefore returned the identity native
            // is constructed with, and the identity is the worst possible wrong answer because it is
            // indistinguishable from a real pose at the world origin. That identity became the
            // doodle's co-registration frame, `markCenterLocal`'s frame, and (since Phase 2)
            // `captureAnchorCam`, leaving the partition composed through a stray `A_est⁻¹`.
            // The baseline was taken by setInitialAnchorFromCapture at the instant it raised the
            // request — the only point that does not race the GL thread's establishment. Waiting for
            // a generation newer than it is what makes a RE-capture correct: a boolean "has one ever
            // been established" is permanently true after the first, so every later capture resolved
            // instantly against the previous anchor's pose.
            val anchor = slamManager.awaitAnchorTransform(slamManager.captureAnchorGenerationBaseline)
            if (anchor == null || anchor.size != 16) {
                MetricDoodleBuilder.recordPreconditionFailure(
                    "anchor never established for this capture — held steady long enough?",
                )
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "Couldn't lock an anchor for this Doodle — hold the phone steady on the " +
                            "wall and try again.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                return@launch
            }
            // A doodle is persisted INTO a project, so without one there is nowhere to put it.
            // This was a bare `?: return@launch`: the artist aimed, tapped, confirmed, and nothing
            // happened — no target, no error, no clue that the missing piece was a project.
            val currentProject = projectRepository.currentProject.value
            if (currentProject == null) {
                MetricDoodleBuilder.recordPreconditionFailure("no project open to save the target into")
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "No project open — a Doodle is saved into a project. Open or create one first.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                return@launch
            }
            val fp = MetricDoodleBuilder.buildSingle(
                slamManager, bitmap, view, intr, planePoint, planeNormal, anchor,
                rotationDeg = rotationDeg,
                mask = selectionMask,
                features = doodleFeatures.value,
            )
            if (fp == null) {
                // Say WHICH way it fell short. "Not enough texture" was the same message whether the
                // frame was blank, out of focus, or full of features that simply missed the plane —
                // three problems with three different fixes, and no way to tell them apart.
                val detected = MetricDoodleBuilder.lastDetected
                val placed = MetricDoodleBuilder.lastPlaced
                val need = MetricDoodleBuilder.lastRequired
                val message = when {
                    detected < need ->
                        "Only $detected features on this wall (need $need). Too dark, too smooth, " +
                            "or out of focus — try a more detailed patch or more light."
                    else ->
                        "Found $detected features but only $placed landed on the wall surface " +
                            "(need $need). Aim at the middle of the detected wall, square on."
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            // Canonical patch (keyframe of the marks) for the distortion head — fed live AND persisted.
            val patch = grayPatchBytes(bitmap)
            slamManager.setWallPatchBytes(patch, PATCH_SIZE)
            slamManager.setWallNormalEnu(null) // a new target: its wall normal is learned afresh

            // Target capture is a writer of the SAME project.json as the editor's design-layer save and
            // AR's wall-feature-map save. A raw whole-object saveProject here (as this used to be) would
            // bypass the repository's atomic transform and could silently clobber whichever of those two
            // ran concurrently (or be clobbered by them) — see docs/AUDIT.md save-race. Route through
            // updateProject { } instead, and only copy the fields target-capture owns onto `current`.
            val baseTargetUris = projectRepository.currentProject.value?.targetImageUris ?: currentProject.targetImageUris
            val updatedTargetUris = projectManager.appendTargetImage(context, currentProject.id, baseTargetUris, bitmap)
            // Area progress samples each design cell's bare-wall colour from this photo.
            slamManager.setCaptureImage(bitmap, intr)
            // A clean-wall photo taken earlier registers against this capture photo just now. If it could
            // not, say so while the artist is still at the wall and can retake it.
            if (slamManager.cleanWallStatus() == com.hereliesaz.graffitixr.nativebridge.SlamManager.CleanWallStatus.FAILED) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, context.getString(com.hereliesaz.graffitixr.design.R.string.ar_feedback_clean_wall_register_failed), Toast.LENGTH_LONG).show()
                }
            }
            // Guarded on id: see the depth path above.
            var committed = false
            projectRepository.updateProject { current ->
                if (current.id != currentProject.id) return@updateProject current
                committed = true
                current.copy(
                    doodle = fp.copy(patchData = patch),
                    // New capture = new doodle frame; paint placed in the old one is invalid,
                    // and so is the wall feature map (its points are in the old frame too).
                    paintMarks = null,
                    paintGrid = null,
                    wallFeatureMap = null,
                    // Persist the capture's intrinsics + anchor + view — the exact values just fed to
                    // restoreWallDoodleMetric — so reload relocalizes with the true intrinsics
                    // and keeps the plane-guided rectification that the capture session had.
                    doodleIntrinsics = intr.toList(),
                    doodleAnchor = anchor.toList(),
                    // DISPLAY-oriented, matching what ingestSingle just handed native (0.10) and the
                    // frame the stored 3D points are in. Persisting the raw sensor-frame view here
                    // would have made 0.10 regress the moment the project was reloaded: the runtime
                    // doodle would be consistent and its saved copy would not.
                    doodleViewMatrix =
                        MetricMarks.glViewDisplayOriented(view, rotationDeg).toList(),
                    // 0.11 — which display frame that was. -1 elsewhere means "genuinely unknown".
                    doodleCaptureRotationDeg = rotationDeg,
                    // A new target may be another wall: its normal is learned afresh.
                    doodleWallNormalEnu = emptyList(),
                    // The full record of how and where the device was held at capture. The
                    // doodle's geometry is frozen at that instant, so these are the conditions
                    // any later attribution has to work from — and they exist nowhere else.
                    captureEnvironment = captureEnvironment,
                    targetImageUris = updatedTargetUris,
                )
            }
            if (!committed) {
                projectManager.deleteTargetImages(updatedTargetUris - baseTargetUris.toSet())
                notifyProjectChangedDuringSave()
                return@launch
            }
            // The pruned entries are no longer referenced now that the list is committed.
            projectManager.deleteTargetImages(baseTargetUris - updatedTargetUris.toSet())
            projectRepository.loadProject(currentProject.id)
            _uiState.update { it.copy(targetCapturedThisSession = true) }
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Doodle Saved & Locked", Toast.LENGTH_SHORT).show()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // An IOException from appendTargetImage / the project write (disk full, project dir
            // gone) used to escape this viewModelScope coroutine and take the whole app down.
            onTargetSaveFailed(e)
        } finally {
            // A build that failed or was abandoned leaves the replacement gate up; restore the old pair.
            slamManager.abortDoodleReplacement()
            _uiState.update { it.copy(isConfirmingTarget = false) }
        } }
    }

    /** The project was switched between the target tap and the save; nothing was written. */
    private suspend fun notifyProjectChangedDuringSave() {
        MetricDoodleBuilder.recordPreconditionFailure("project changed before the target was saved")
        withContext(Dispatchers.Main) {
            Toast.makeText(
                context,
                "Another project was opened before the Doodle saved — it was not saved. Capture it again.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    /** A target save threw (I/O, native); report it instead of crashing the app. */
    private suspend fun onTargetSaveFailed(e: Exception) {
        timber.log.Timber.e(e, "Target save failed")
        MetricDoodleBuilder.recordPreconditionFailure(
            "target save failed: ${e.message ?: e.javaClass.simpleName}",
        )
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "Couldn't save the Doodle. Try again.", Toast.LENGTH_LONG).show()
        }
    }

    // Shared guidance shown when single-capture target creation lands on no tracked wall plane at all
    // (used by both the pre-review gate and the handleSingleCapture backstop). Toast-in-VM matches the
    // existing capture toasts here; a full AppStrings/event-channel i18n pass is a separate cleanup.
    //
    // No longer says "green": any tracked plane under the tap is accepted now (see the wall-plane
    // hit-test in ArRenderer), so the only real failure is aiming where no surface has been detected.
    private val notOnGreenWallMessage =
        "No wall detected there yet. Sweep the phone across the surface until it's outlined, then tap."

    /**
     * The single-capture tap wasn't on any tracked wall plane (the plane no longer has to be
     * green — see the hit-test in ArRenderer). Guide the artist; the capture frame is discarded
     * separately (ArViewModel.clearCaptureForRetry) so they stay in tap mode and can simply
     * re-aim and tap again.
     */
    fun notifyTargetNotOnWall() {
        Toast.makeText(context, notOnGreenWallMessage, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val PATCH_SIZE = 256 // distortion-head canonical patch is 256x256 gray
    }
}
