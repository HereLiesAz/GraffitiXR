// FILE: core/nativebridge/src/main/java/com/hereliesaz/graffitixr/nativebridge/SlamManager.kt
package com.hereliesaz.graffitixr.nativebridge

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.util.Log
import com.hereliesaz.graffitixr.common.model.Fingerprint
import com.hereliesaz.graffitixr.common.model.RelocDiagnostics
import com.hereliesaz.graffitixr.common.model.RelocReject
import com.hereliesaz.graffitixr.common.model.WallFeatureMap
import com.hereliesaz.graffitixr.common.sensor.CameraFrame
import com.hereliesaz.graffitixr.common.sensor.ImuSample
import com.hereliesaz.graffitixr.common.sensor.PixelFormat
import com.hereliesaz.graffitixr.common.util.NativeLibLoader
import com.hereliesaz.graffitixr.common.wearable.WearableManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Singleton
class SlamManager @Inject constructor(
    private val wearableManager: WearableManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collectionJob: Job? = null

    init {
        NativeLibLoader.loadAll()
    }

    // Guards native init/destroy. ensureInitialized() and destroy() are called from the
    // GL thread, the sensor (Default) scope, and the UI thread; without this lock two
    // threads could both pass the !isInitialized check and double-call nativeInitialize(),
    // or init could race a concurrent destroy() (use-after-free in native code).
    private val initLock = Any()
    @Volatile private var isInitialized = false

    fun ensureInitialized() {
        synchronized(initLock) {
            if (!isInitialized) {
                nativeInitialize()
                isInitialized = true
            }
        }
    }

    fun prepareLiquify(bitmap: Bitmap) = nativePrepareLiquify(bitmap)
    fun applyLiquify(stroke: FloatArray, brushSize: Float, intensity: Float) = nativeApplyLiquify(stroke, brushSize, intensity)
    fun drawLiquify(width: Int, height: Int) = nativeDrawLiquify(width, height)
    fun bakeLiquify(outBitmap: Bitmap) = nativeBakeLiquify(outBitmap)

    fun getSplatCount(): Int = nativeGetSplatCount()
    fun getImmutableSplatCount(): Int = nativeGetImmutableSplatCount()
    fun getVisibleConfidenceAvg(): Float = nativeGetVisibleConfidenceAvg()
    fun getGlobalConfidenceAvg(): Float = nativeGetGlobalConfidenceAvg()
    fun setSplatsVisible(visible: Boolean) = nativeSetSplatsVisible(visible)
    fun getLastDepthTrace(): String = nativeGetLastDepthTrace()
    fun getLastSplatTrace(): String = nativeGetLastSplatTrace()

    /**
     * How many times the renderer has ESTABLISHED the primary anchor this session — not how many
     * times an anchor pose has been written, and not merely whether one ever was.
     *
     * Two distinctions, each of which a simpler design got wrong.
     *
     * **Establishment, not any write.** `mAnchorMatrix` is constructed as the identity but does not
     * stay that way: `refineAnchorFromBestPlane` runs every 30 frames *while the anchor is
     * unestablished* and writes a provisional plane pose, as does the depth fallback. A flag set by
     * `updateAnchorTransform` therefore flips within a second of scanning. Worse, the refiner builds
     * its basis with the wall normal in **Z** while establishment takes ARCore's `hitPose`, whose
     * normal is **+Y** — reading one where the other is meant is a ~90° frame error, not drift.
     *
     * **A counter, not a latch.** Every target confirmation re-establishes the anchor, so a boolean
     * "has one ever been established" is permanently true after the first capture and every capture
     * after it resolves instantly against the *previous* anchor's pose — the same off-by-an-anchor
     * bug the flag existed to prevent. A caller snapshots this before requesting establishment and
     * waits for it to advance.
     */
    private val _anchorGeneration = kotlinx.coroutines.flow.MutableStateFlow(0)
    val anchorGeneration: kotlinx.coroutines.flow.StateFlow<Int> = _anchorGeneration

    /**
     * Whether an anchor exists right now, as distinct from how many have existed.
     *
     * Needed because the counter must be **monotonic**. Resetting it to 0 on teardown lets it go
     * backwards, which strands any capture already waiting for `> N`: the next establishment
     * produces 1, which is not greater than 1, so the wait burns its whole budget and reports
     * "couldn't lock an anchor" for what was actually a torn-down session. Advancing the generation
     * on teardown and clearing this flag says the same thing without lying about ordering.
     */
    @Volatile private var hasAnchor = false

    /**
     * Bumped every time the AR session is torn down, so a wait started in one session cannot be
     * satisfied by an anchor established in the next.
     *
     * The monotonic generation counter alone cannot express this. It fixed a real bug — resetting to
     * zero stranded in-flight waits — but it also turned a fail-CLOSED outcome into a fail-OPEN one:
     * teardown then re-entry produces a generation strictly greater than the waiter's baseline with
     * `hasAnchor` true again, so the wait resolves and hands the new session's anchor to the old
     * session's capture. That capture back-projects its pixels through a foreign anchor and persists
     * a structurally valid, geometrically meaningless target. Refusing is the correct outcome, and
     * `PARAMETERS.md` §6 already states it as the contract.
     */
    @Volatile private var sessionEpoch = 0

    /** Readable so a requester can pin it alongside [captureAnchorGenerationBaseline]. */
    val sessionEpochValue: Int get() = sessionEpoch

    /**
     * The anchor generation as of the moment a capture asked for a new anchor.
     *
     * Lives here rather than being snapshotted by the waiter because only the requester can take it
     * without racing: the request and the establishment are on different threads, and a snapshot
     * taken after the request can already include the establishment it was meant to precede.
     */
    @Volatile var captureAnchorGenerationBaseline: Int = 0

    /**
     * The session epoch as of the same instant [captureAnchorGenerationBaseline] was taken.
     *
     * Pinned with the baseline rather than at the start of the wait, for the same reason the
     * baseline is: the wait begins a dispatcher hop later, and an epoch read there can already
     * include the teardown it was meant to exclude.
     */
    @Volatile var captureSessionEpochBaseline: Int = 0

    fun updateAnchorTransform(transform: FloatArray) = nativeUpdateAnchorTransform(transform)

    /**
     * Announce that the primary anchor has been (re-)established. Call from the renderer at the
     * moment it sets its own `anchorEstablished`, and nowhere else — every other anchor write is
     * provisional.
     */
    @Synchronized
    fun markAnchorEstablished() {
        // Synchronized because this is a read-modify-write on the GL thread racing teardown on the
        // main thread; @Volatile alone would let one of the two updates be lost.
        hasAnchor = true
        _anchorGeneration.value = _anchorGeneration.value + 1
    }

    /**
     * The anchor pose, waiting up to [timeoutMs] for an establishment **newer than**
     * [sinceGeneration] — which the capture path takes from [captureAnchorGenerationBaseline],
     * recorded by the requester rather than snapshotted here, because only the requester can take it
     * without racing the establishment it is meant to precede.
     *
     * Returns null on timeout rather than a pose, because every candidate fallback is a well-formed
     * matrix that means the wrong thing: the identity reads as a real pose at the world origin, the
     * plane refiner's provisional pose is in a different frame convention, and the previous
     * anchor's pose is a plausible answer to a question about a different anchor.
     */
    suspend fun awaitAnchorTransform(
        sinceGeneration: Int,
        timeoutMs: Long = ANCHOR_WAIT_MS,
        sinceEpoch: Int = captureSessionEpochBaseline,
    ): FloatArray? {
        // An anchor from a LATER session answers a different question than the one this capture
        // asked, so the wait must expire rather than accept it. Defaulted from the requester's
        // pinned value, not read here — reading here is a dispatcher hop too late.
        val epochAtEntry = sinceEpoch
        if (!(hasAnchor && _anchorGeneration.value > sinceGeneration)) {
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                _anchorGeneration.first { it > sinceGeneration && hasAnchor && sessionEpoch == epochAtEntry }
            } ?: return null
        }
        if (sessionEpoch != epochAtEntry) return null
        val m = nativeGetAnchorTransform()
        // Native hands back a freshly ZEROED array when the engine is gone, which passes a bare
        // size check and yields a singular matrix — strictly worse than the identity this exists to
        // avoid. A real anchor pose has a unit-length first rotation column; all three writers
        // produce orthonormal matrices, so this rejects only genuinely broken input.
        if (m == null || m.size != 16) return null
        val c0 = kotlin.math.sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2])
        return if (kotlin.math.abs(c0 - 1f) < 1e-3f) m else null
    }

    /**
     * Record that the anchor is gone. Call when tearing the AR session down: the ARCore session and
     * its anchors do not survive it.
     *
     * Advances the generation rather than resetting it, so the counter only ever moves forwards. A
     * reset to 0 makes old snapshots compare as *larger* than new generations — the opposite of the
     * ordering the wait depends on — and strands any capture already inside it.
     */
    @Synchronized
    fun clearAnchorEstablished() {
        hasAnchor = false
        sessionEpoch += 1
        _anchorGeneration.value = _anchorGeneration.value + 1
    }

    /**
     * Centroid of the matched fingerprint marks, expressed in the FINGERPRINT ANCHOR's local frame
     * (the anchor PoseFusion converges the live render anchor toward), or null to fall back to the
     * anchor's own position. Anchor-local (not world) so it survives a project reload into a new
     * ARCore session: the renderer reconstructs the world point from the live anchor each frame. Set
     * by the AR fingerprint builder on capture and re-published on project load; read by the AR
     * renderer to center the artwork overlay on the marks instead of the screen-center anchor. Plain
     * Kotlin state (not native) shared across the view models and the GL thread, hence @Volatile.
     */
    @Volatile var overlayMarkCenterLocal: FloatArray? = null

    fun updateDeviceMotion(angularVel: FloatArray, linearVel: FloatArray) {
        nativeUpdateDeviceMotion(angularVel, linearVel)
    }

    /**
     * Fraction of the registered design the wall now answers for — a PROGRESS reading, on the
     * timescale of hours and roughly monotonic. This is the number to show the artist.
     */
    fun getPaintingProgress(): Float = nativeGetPaintingProgress()

    /**
     * How strongly the wall corroborates the design on the most recent look — a CONFIDENCE reading,
     * on the timescale of frames and moving both ways. This is the number [PoseFusion-style] pose
     * correction should scale by, NOT painting progress: one scalar cannot mean both "the mural is
     * 60% painted" and "I trust this frame", and using progress for both meant a momentary tracking
     * hiccup decayed the progress bar and suppressed correction strength for seconds afterwards.
     *
     * @return `[0,1]` once measured, or **negative** if no attempt has produced a measurement yet.
     *   That is deliberately distinct from `0f`, which means "looked, and the wall agrees with
     *   nothing". Callers feeding a `[0,1]` API should map the negative case to their own
     *   conservative default rather than passing it through.
     */
    fun getCorroborationConfidence(): Float = nativeGetCorroborationConfidence()

    /** True once a corroboration attempt has produced any measurement at all. */
    fun hasCorroborationMeasurement(): Boolean = nativeGetCorroborationConfidence() >= 0f

    /**
     * Why the last relocalization attempt did not publish a pose, and how far it got. See
     * [RelocDiagnostics]; the native side packs NINE values into one int[] so the read is a
     * consistent snapshot rather than nine racing getters. (Three separate comments used to say
     * three, four and six; the array was six wide until 2.11 added the three backbone counts.)
     *
     * A short array falls back to the default [RelocDiagnostics] — whose backbone fields are the -1
     * "not measured" sentinel, not 0 — rather than to a partially-filled one, so an old .so paired
     * with this build reads as unmeasured instead of as an empty backbone.
     */
    fun getRelocDiagnostics(): RelocDiagnostics {
        val v = nativeGetRelocDiagnostics()
        if (v == null || v.size < 9) return RelocDiagnostics()
        return RelocDiagnostics(
            reject = RelocReject.entries.getOrElse(v[0]) { RelocReject.UNKNOWN },
            matches = v[1],
            inliers = v[2],
            detected = v[3],
            obliquityDeg = v[4],
            rectifiedCorrespondences = v[5],
            backboneFeatures = v[6],
            backboneMatches = v[7],
            backboneInliers = v[8],
        )
    }

    fun getAnchorTransform(): FloatArray = nativeGetAnchorTransform()

    fun setWallFingerprint(
        bitmap: Bitmap,
        mask: Bitmap?,
        depthBuffer: ByteBuffer,
        depthW: Int, depthH: Int, depthStride: Int,
        intrinsics: FloatArray,
        viewMatrix: FloatArray
    ): Fingerprint? {
        if (!depthBuffer.isDirect) return null
        return nativeSetWallFingerprint(bitmap, mask, depthBuffer, depthW, depthH, depthStride, intrinsics, viewMatrix)
    }

    fun restoreWallFingerprint(descriptorsData: ByteArray, rows: Int, cols: Int, type: Int, points3d: FloatArray) {
        nativeRestoreWallFingerprint(descriptorsData, rows, cols, type, points3d)
    }

    /**
     * Ingest a fingerprint built from triangulated metric marks (no depth source). Also fixes the
     * fingerprint anchor pose (column-major 4x4) and the camera intrinsics (fx,fy,cx,cy) the reloc
     * PnP should use. points3d are in keyframe-0's CV camera frame (see [MetricMarks]).
     *
     * [viewMatrix] is the GL-convention world->camera view at capture. Supplying it enables the
     * reloc thread's plane-guided rectification: the marks lie on a known plane, so the oblique-
     * vs-frontal distortion is a homography that can be pre-cancelled before matching. Pass an empty
     * array only when the capture view genuinely isn't known (e.g. a project saved before it was
     * persisted) — then that pass is skipped rather than run against a stale frontal frame.
     *
     * [regions] is the Phase-2 partition: one byte per 3D point, holding a `Footprint.Region`
     * ordinal. The reloc correspondence build excludes the points tagged `INSIDE` — they sit under
     * the artwork and decay as it is painted. **An empty array means all-backbone**, which is
     * exactly the behaviour before Phase 2, so a legacy fingerprint keeps relocalizing against its
     * whole map rather than losing it. A non-empty array of the wrong length is rejected native-side
     * rather than silently truncating, because the partition is indexed by point.
     */
    fun restoreWallFingerprintMetric(
        descriptorsData: ByteArray, rows: Int, cols: Int, type: Int,
        points3d: FloatArray, anchorMatrix: FloatArray, intrinsics: FloatArray,
        viewMatrix: FloatArray = FloatArray(0),
        regions: ByteArray = ByteArray(0),
    ) {
        nativeRestoreWallFingerprintMetric(
            descriptorsData, rows, cols, type, points3d, anchorMatrix, intrinsics, viewMatrix,
            regions,
        )
    }

    /**
     * Drop the in-native wall fingerprint. The restore calls above only ever REPLACE the stored
     * fingerprint, so without this it is process-lifetime state: a project that has no fingerprint of
     * its own would keep relocalizing against whatever wall was fingerprinted earlier in the session
     * (notably the marks built during first-run onboarding). Call it when loading a project that has
     * no saved fingerprint, alongside [clearWallFeatureMap].
     */
    fun clearWallFingerprint() = nativeClearWallFingerprint()

    /** Restore the persistent wall feature map into native (Phase 2a: stored; matched in Phase 2b). */
    fun restoreWallFeatureMap(map: WallFeatureMap) {
        nativeRestoreWallFeatureMap(
            map.descriptorsData, map.descriptorsRows, map.descriptorsCols, map.descriptorsType,
            map.points3d, map.confidence, map.obsCount, map.anchor, map.intrinsics,
        )
    }
    /** Drop the in-native wall feature map. */
    fun clearWallFeatureMap() = nativeClearWallFeatureMap()
    /** Live wall-feature-map point count — diagnostic. */
    fun getMapPointCount(): Int = nativeGetMapPointCount()
    /** Phase 2b: enable live map-matching in reloc. Default OFF — experimental until device-validated. */
    fun setMapRelocEnabled(enabled: Boolean) = nativeSetMapRelocEnabled(enabled)
    /** Phase 3: passively grow the feature map from reloc-locked frames. Default OFF; independent of matching. */
    fun setMapBuildEnabled(enabled: Boolean) = nativeSetMapBuildEnabled(enabled)

    /**
     * Phase 3b: read the in-native feature map back as a [WallFeatureMap] for .gxr persistence, or null
     * when empty. Unpacks the native little-endian blob: [n, rows, cols, type][points][conf][obs][anchor16]
     * [intrinsics4][descriptors]. Returns null (skip this save) if a concurrent grow left it inconsistent.
     */
    fun getWallFeatureMap(): WallFeatureMap? {
        val blob = nativeExportWallFeatureMap() ?: return null
        if (blob.size < 16) return null
        val bb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        val n = bb.int; val rows = bb.int; val cols = bb.int; val type = bb.int
        if (n < 0 || rows < 0 || cols < 0) return null
        // Bail before reading if the blob can't even hold the fixed-size fields (header + points/conf/obs
        // + anchor + intrinsics = 96 + n*20 bytes), rather than catching a BufferUnderflowException.
        if (blob.size < 96 + n * 20) return null
        return try {
            val points = FloatArray(n * 3) { bb.float }
            val conf = FloatArray(n) { bb.float }
            val obs = IntArray(n) { bb.int }
            val anchor = FloatArray(16) { bb.float }
            val intrinsics = FloatArray(4) { bb.float }
            val desc = ByteArray(blob.size - bb.position()).also { bb.get(it) }
            WallFeatureMap(points, desc, rows, cols, type, conf, obs, anchor, intrinsics)
        } catch (e: Exception) {
            null
        }
    }

    fun setArtworkFingerprint(
        bitmap: Bitmap,
        depthBuffer: ByteBuffer?,
        depthW: Int, depthH: Int, depthStride: Int,
        intrinsics: FloatArray,
        viewMatrix: FloatArray
    ) {
        // Depth is optional: with the ML depth API off there is no capture depth buffer, so the artwork
        // base registers descriptors-only (enough for painting-progress; 3D-dependent promotion waits).
        if (depthBuffer == null || depthBuffer.isDirect) {
            nativeSetArtworkFingerprint(bitmap, depthBuffer, depthW, depthH, depthStride, intrinsics, viewMatrix)
        }
    }

    fun setViewportSize(width: Int, height: Int) = nativeSetViewportSize(width, height)

    fun clearMap() = nativeClearMap()
    fun pruneByConfidence(threshold: Float) = nativePruneByConfidence(threshold)

    fun setRelocEnabled(enabled: Boolean) = nativeSetRelocEnabled(enabled)
    /** Teleological self-grow (default ON): promote validated new marks into the live fingerprint. */
    fun setSelfGrowEnabled(enabled: Boolean) = nativeSetSelfGrowEnabled(enabled)

    /**
     * `EVALUATION.md` §3.1 / `IMPLEMENTATION.md` 6a.4 — fix OpenCV's RNG so a replayed eval run is
     * reproducible. **Evaluation only.**
     *
     * `solvePnPRansac` draws random samples, so two replays of the same recording can disagree, and
     * an A/B of two parameter values then reports RANSAC variance as a parameter effect. §3.1 calls
     * this out as "the single most common way a tuning exercise produces confident nonsense".
     *
     * A **negative** seed means "leave the RNG alone" and is the default, so this is inert unless an
     * eval run turns it on. [setEvalRngSeedIfDebuggable] is the safer entry point; call this one
     * only from code that has already established it is not a release build.
     *
     * Note the seed is applied immediately before every PnP solve rather than once at start-up: the
     * reloc thread shares the global `cv::theRNG()` with other consumers, so a single seeding would
     * drift as soon as anything else drew from it.
     */
    fun setEvalRngSeed(seed: Long) = nativeSetEvalRngSeed(seed)

    /**
     * [setEvalRngSeed], but a no-op unless [debuggable] is true — the guard that keeps 6a.4's
     * "must be inert in release builds" true at the call site rather than by convention.
     *
     * Pass `BuildConfig.DEBUG` (or the app's own debuggable check). A fixed RANSAC seed shipped to
     * users would make every device draw the identical sample sequence forever, which is a
     * behaviour change and not an evaluation affordance.
     */
    fun setEvalRngSeedIfDebuggable(seed: Long, debuggable: Boolean) {
        if (debuggable) nativeSetEvalRngSeed(seed)
    }

    /** Restore production behaviour: stop seeding, let RANSAC draw freely again. */
    fun clearEvalRngSeed() = nativeSetEvalRngSeed(-1L)

    /**
     * SuperPoint detect+describe on [bitmap] (gray + CLAHE applied natively). Returns the packed array
     * [n, dim, (u,v)*n, descriptors row-major (n*dim)], or null if the model isn't loaded / nothing
     * found. Caller unpacks (kept here as a raw array to avoid an OpenCV dependency in this module).
     */
    fun detectSuperPoint(bitmap: Bitmap): FloatArray? = nativeDetectSuperPoint(bitmap)

    /** Live wall-fingerprint point count — diagnostic for reloc health / watching self-grow. */
    fun getWallKeypointCount(): Int = nativeGetWallKeypointCount()

    /** Store the canonical fingerprint patch (the captured marks) for the distortion head. */
    fun setWallPatch(bitmap: Bitmap) = nativeSetWallPatch(bitmap)
    /** Store the canonical patch from a raw [size]x[size] gray byte buffer (persisted on Fingerprint). */
    fun setWallPatchBytes(data: ByteArray, size: Int) = nativeSetWallPatchBytes(data, size)
    fun setVoxelSize(size: Float) = nativeSetVoxelSize(size)
    /** Minimum angular baseline (degrees) for a re-observation to count as a parallax depth check. */
    fun setParallaxMinDegrees(deg: Float) = nativeSetParallaxMinDegrees(deg)
    fun setMappingPaused(paused: Boolean) = nativeSetMappingPaused(paused)

    fun initGl() {
        nativeInitGl()
    }

    fun resetGlContext() {
        nativeResetGlContext()
    }

    /** Voxel-only GL init — split out so a caller can localize a GL-init stall on-screen. */
    fun initVoxelGl() {
        // Guard so a premature call surfaces as a failure breadcrumb instead of a silent native
        // no-op (gSlamEngine null) that would falsely print "voxel ok".
        check(isInitialized) { "SlamManager is not initialized" }
        nativeInitVoxelGl()
    }

    /** Voxel program (shader compile/link) GL init — split out to localize a stall on-screen. */
    fun initVoxelGlProgram() {
        check(isInitialized) { "SlamManager is not initialized" }
        nativeInitVoxelGlProgram()
    }

    /** Voxel buffer (11MB VBO alloc) GL init — split out to localize a stall on-screen. */
    fun initVoxelGlBuffer() {
        check(isInitialized) { "SlamManager is not initialized" }
        nativeInitVoxelGlBuffer()
    }

    /** Mesh-only GL init — split out so a caller can localize a GL-init stall on-screen. */
    fun initMeshGl() {
        check(isInitialized) { "SlamManager is not initialized" }
        nativeInitMeshGl()
    }

    fun updateCamera(
        viewMatrix: FloatArray,
        projectionMatrix: FloatArray,
        mappingViewMatrix: FloatArray,
        mappingProjectionMatrix: FloatArray,
        timestampNs: Long
    ) {
        nativeUpdateCamera(viewMatrix, projectionMatrix, mappingViewMatrix, mappingProjectionMatrix, timestampNs)
    }

    fun feedArCoreDepth(
        depthBuffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        intrinsics: FloatArray,
        intrW: Int,
        intrH: Int,
        cvRotateCode: Int? = null,
        confidence: Float = 0.5f
    ) {
        if (depthBuffer.isDirect) {
            nativeFeedArCoreDepth(depthBuffer, width, height, rowStride, intrinsics, intrW, intrH, cvRotateCode ?: -1, confidence)
        }
    }

    fun feedPointCloud(points: FloatArray) {
        nativeFeedPointCloud(points)
    }

    fun feedYuvFrame(
        yBuffer: ByteBuffer,
        uBuffer: ByteBuffer,
        vBuffer: ByteBuffer,
        width: Int,
        height: Int,
        yStride: Int,
        uvStride: Int,
        uvPixelStride: Int,
        timestampNs: Long,
        cvRotateCode: Int? = null
    ) {
        if (yBuffer.isDirect && uBuffer.isDirect && vBuffer.isDirect) {
            nativeFeedYuvFrame(yBuffer, uBuffer, vBuffer, width, height, yStride, uvStride, uvPixelStride, timestampNs, cvRotateCode ?: -1)
        }
    }

    fun feedColorFrame(colorBuffer: ByteBuffer, width: Int, height: Int, timestampNs: Long, cvRotateCode: Int? = null) {
        if (colorBuffer.isDirect) {
            nativeFeedColorFrame(colorBuffer, width, height, timestampNs, cvRotateCode ?: -1)
        }
    }

    /**
     * Renders the engine's active representation (voxel splats / surface mesh). [debugTint]
     * recolours splats by confidence (cyan→magenta) for the perception debug view — raw splats
     * carry camera colours and are invisible against the very surface they reconstruct.
     */
    fun draw(debugTint: Boolean = false) {
        nativeDraw(debugTint)
    }

    /**
     * Debug perception view: draws the requested SLAM representations explicitly, independent of
     * scan/mural mode. Voxels are confidence-tinted (cyan→magenta) and depth-off. Mesh is the
     * persistent surface mesh. The accumulated sparse point cloud is a separate Kotlin renderer.
     */
    fun drawDebugLayers(voxels: Boolean, mesh: Boolean) = nativeDrawDebugLayers(voxels, mesh)

    /** Voxel-method colour-mask: draws the splats as a confidence-graded colour wash over the grayscale camera. */
    fun drawCoverage() = nativeDrawCoverage()

    fun feedStereoData(leftBuffer: ByteBuffer, rightBuffer: ByteBuffer, width: Int, height: Int, timestamp: Long) {
        if (leftBuffer.isDirect && rightBuffer.isDirect) {
            nativeFeedStereoData(leftBuffer, rightBuffer, width, height, timestamp)
        }
    }

    fun setArCoreTrackingState(isTracking: Boolean) {
        nativeSetArCoreTrackingState(isTracking)
    }

    fun saveModel(path: String) {
        nativeSaveModel(path)
    }

    fun loadModel(path: String) {
        nativeLoadModel(path)
    }

    fun importModel3D(path: String): Boolean {
        return nativeImportModel3D(path)
    }

    fun loadSuperPoint(assetManager: AssetManager): Boolean = nativeLoadSuperPoint(assetManager)
    /** Optional distortion head (docs/DISTORTION_HEAD.md). False (inert) if the asset isn't bundled. */
    fun loadDistortionHead(assetManager: AssetManager): Boolean = nativeLoadDistortionHead(assetManager)
    fun loadLowLightEnhancer(assetManager: AssetManager) = nativeLoadLowLightEnhancer(assetManager)


    /** Eval (Sub-project A): average ms/stage since last call, then resets native accumulators.
     *  Indexes: 0=voxelUpdate,1=voxelKeyframe,2=surfaceMesh,3=draw,4=pnpReloc. */
    fun getStageTimings(): FloatArray {
        val out = FloatArray(5)
        nativeGetStageTimings(out)
        return out
    }

    /** Eval: toggle a native stage for A/B cost attribution. Stage 0 is non-gateable (reloc backbone). */
    fun setStageEnabled(stage: Int, enabled: Boolean) = nativeSetStageEnabled(stage, enabled)

    /** Pose fusion (B): [0..15]=pnpMat, [16]=inlierCount, [17]=matchCount, [18]=seq. */
    fun getRelocResult(): FloatArray { val o = FloatArray(19); nativeGetRelocResult(o); return o }

    /** Pose fusion (B): the anchor model matrix captured in the fingerprint world frame. */
    fun getFingerprintAnchor(): FloatArray { val o = FloatArray(16); nativeGetFingerprintAnchor(o); return o }

    fun getPersistentMesh(vertices: FloatArray, weights: FloatArray) = nativeGetPersistentMesh(vertices, weights)
    fun unrollMesh(vertices: FloatArray): FloatArray = nativeUnrollMesh(vertices)

    fun exportFingerprint(): ByteArray? = nativeExportFingerprint()
    fun alignToFingerprint(data: ByteArray) = nativeAlignToFingerprint(data)

    /** Co-op alias: align local SLAM state to the peer's fingerprint bytes. */
    fun alignToPeer(fingerprint: ByteArray) = alignToFingerprint(fingerprint)

    fun getAnchorCandidates(threshold: Float, maxCount: Int): FloatArray? {
        return nativeGetAnchorCandidates(threshold, maxCount)
    }

    fun startSensorCollection() {
        collectionJob?.cancel()
        collectionJob = scope.launch {
            wearableManager.activeSensorSource.collectLatest { source ->
                launch {
                    source.cameraFrames.collect { frame -> forwardFrame(frame) }
                }
                launch {
                    source.imuSamples.collect { sample -> forwardImu(sample) }
                }
            }
        }
    }

    fun stopSensorCollection() {
        collectionJob?.cancel()
        collectionJob = null
    }

    private fun forwardFrame(frame: CameraFrame) {
        if (!frame.pixels.isDirect) {
            Log.w(TAG, "skipping non-direct ByteBuffer frame")
            return
        }
        when (frame.format) {
            PixelFormat.RGBA_8888 -> {
                feedColorFrame(frame.pixels, frame.width, frame.height, frame.timestampNs, null)
            }
            PixelFormat.YUV_420_888 -> forwardYuvFrame(frame)
        }
    }

    private fun forwardYuvFrame(frame: CameraFrame) {
        val layout = frame.yuvLayout
        if (layout == null) {
            Log.w(TAG, "YUV frame missing yuvLayout — dropping")
            return
        }
        val full = frame.pixels
        val y = sliceDirect(full, layout.yOffset, layout.ySize) ?: return
        val u = sliceDirect(full, layout.uOffset, layout.uSize) ?: return
        val v = sliceDirect(full, layout.vOffset, layout.vSize) ?: return
        feedYuvFrame(
            yBuffer = y,
            uBuffer = u,
            vBuffer = v,
            width = frame.width,
            height = frame.height,
            yStride = layout.yStride,
            uvStride = layout.uvStride,
            uvPixelStride = layout.uvPixelStride,
            timestampNs = frame.timestampNs,
            cvRotateCode = null,
        )
    }

    /** Returns a direct-byte-buffer view over [offset, offset+size) of [src]. */
    private fun sliceDirect(src: ByteBuffer, offset: Int, size: Int): ByteBuffer? {
        if (offset < 0 || size <= 0 || offset + size > src.capacity()) {
            Log.w(TAG, "slice out of bounds: off=$offset size=$size cap=${src.capacity()}")
            return null
        }
        val dup = src.duplicate()
        dup.position(offset)
        dup.limit(offset + size)
        val slice = dup.slice()
        return if (slice.isDirect) slice else null
    }

    private fun forwardImu(sample: ImuSample) {
        val gyro = floatArrayOf(sample.gyro.x, sample.gyro.y, sample.gyro.z)
        val accel = floatArrayOf(sample.accel.x, sample.accel.y, sample.accel.z)
        updateDeviceMotion(gyro, accel)
    }

    fun destroy() {
        stopSensorCollection()
        synchronized(initLock) {
            if (isInitialized) {
                nativeDestroy()
                isInitialized = false
            }
        }
    }

    fun annotateKeypoints(bitmap: Bitmap): Bitmap {
        val mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        nativeAnnotateKeypoints(mutable)
        return mutable
    }

    fun getKeypoints(bitmap: Bitmap): List<android.util.Pair<Float, Float>> {
        val raw = nativeGetKeypoints(bitmap) ?: return emptyList()
        val list = mutableListOf<android.util.Pair<Float, Float>>()
        for (i in 0 until raw.size / 2) {
            list.add(android.util.Pair(raw[i * 2], raw[i * 2 + 1]))
        }
        return list
    }

    /**
     * The REAL fingerprint feature positions (image pixels) the same detector used by
     * generateFingerprint would produce on [bitmap], restricted to [mask] when given — for a truthful
     * curation overlay. Unlike getKeypoints (plain ORB-500), this matches what actually anchors.
     */
    fun getFingerprintKeypoints(bitmap: Bitmap, mask: Bitmap?): List<android.util.Pair<Float, Float>> {
        val raw = nativeGetFingerprintKeypoints(bitmap, mask) ?: return emptyList()
        val list = ArrayList<android.util.Pair<Float, Float>>(raw.size / 2)
        for (i in 0 until raw.size / 2) list.add(android.util.Pair(raw[i * 2], raw[i * 2 + 1]))
        return list
    }

    fun updateLightLevel(level: Float) {
        nativeUpdateLightLevel(level)
    }

    // Native methods
    private external fun nativeGetKeypoints(bitmap: Bitmap): FloatArray?
    private external fun nativeGetFingerprintKeypoints(bitmap: Bitmap, mask: Bitmap?): FloatArray?
    private external fun nativeInitialize()

    private external fun nativeInitGl()
    private external fun nativeResetGlContext()
    private external fun nativeInitVoxelGl()
    private external fun nativeInitVoxelGlProgram()
    private external fun nativeInitVoxelGlBuffer()
    private external fun nativeInitMeshGl()
    private external fun nativeSetViewportSize(width: Int, height: Int)
    private external fun nativeUpdateCamera(
        viewMatrix: FloatArray,
        projectionMatrix: FloatArray,
        mappingViewMatrix: FloatArray,
        mappingProjectionMatrix: FloatArray,
        timestampNs: Long
    )
    private external fun nativeUpdateLightLevel(level: Float)
    private external fun nativeDraw(debugTint: Boolean)
    private external fun nativeDrawDebugLayers(voxels: Boolean, mesh: Boolean)
    private external fun nativeDrawCoverage()
    private external fun nativeGetSplatCount(): Int
    private external fun nativeGetImmutableSplatCount(): Int
    private external fun nativeGetVisibleConfidenceAvg(): Float
    private external fun nativeGetGlobalConfidenceAvg(): Float
    private external fun nativeSetSplatsVisible(visible: Boolean)
    private external fun nativeGetLastDepthTrace(): String
    private external fun nativeGetLastSplatTrace(): String
    private external fun nativeSetArCoreTrackingState(isTracking: Boolean)
    private external fun nativeClearMap()
    private external fun nativePruneByConfidence(threshold: Float)
    private external fun nativeSaveModel(path: String)
    private external fun nativeLoadModel(path: String)
    private external fun nativeImportModel3D(path: String): Boolean
    private external fun nativeLoadSuperPoint(assetManager: AssetManager): Boolean
    private external fun nativeLoadDistortionHead(assetManager: AssetManager): Boolean
    private external fun nativeLoadLowLightEnhancer(assetManager: AssetManager)
    private external fun nativeUpdateAnchorTransform(transform: FloatArray)
    private external fun nativeUpdateDeviceMotion(angularVel: FloatArray, linearVel: FloatArray)
    private external fun nativeGetAnchorTransform(): FloatArray
    private external fun nativeGetPaintingProgress(): Float
    private external fun nativeGetCorroborationConfidence(): Float
    private external fun nativeGetRelocDiagnostics(): IntArray?
    private external fun nativeGetStageTimings(out: FloatArray)
    private external fun nativeSetStageEnabled(stage: Int, enabled: Boolean)
    private external fun nativeGetRelocResult(out: FloatArray)
    private external fun nativeGetFingerprintAnchor(out: FloatArray)
    private external fun nativeGetPersistentMesh(vertices: FloatArray, weights: FloatArray)
    private external fun nativeUnrollMesh(vertices: FloatArray): FloatArray
    private external fun nativeExportFingerprint(): ByteArray?
    private external fun nativeGetAnchorCandidates(threshold: Float, maxCount: Int): FloatArray?
    private external fun nativeAlignToFingerprint(data: ByteArray)
    private external fun nativeSetWallFingerprint(
        bitmap: Bitmap, mask: Bitmap?,
        depthBuffer: ByteBuffer,
        depthW: Int, depthH: Int, depthStride: Int,
        intrinsics: FloatArray, viewMatrix: FloatArray
    ): Fingerprint?
    private external fun nativeRestoreWallFingerprint(
        descriptorsData: ByteArray, rows: Int, cols: Int, type: Int, points3d: FloatArray
    )
    private external fun nativeRestoreWallFingerprintMetric(
        descriptorsData: ByteArray, rows: Int, cols: Int, type: Int,
        points3d: FloatArray, anchorMatrix: FloatArray, intrinsics: FloatArray,
        viewMatrix: FloatArray, regions: ByteArray
    )
    private external fun nativeRestoreWallFeatureMap(
        descriptorsData: ByteArray, rows: Int, cols: Int, type: Int,
        points3d: FloatArray, confidence: FloatArray, obsCount: IntArray,
        anchor: FloatArray, intrinsics: FloatArray
    )
    private external fun nativeClearWallFeatureMap()
    private external fun nativeClearWallFingerprint()
    private external fun nativeGetMapPointCount(): Int
    private external fun nativeSetMapRelocEnabled(enabled: Boolean)
    private external fun nativeSetMapBuildEnabled(enabled: Boolean)
    private external fun nativeExportWallFeatureMap(): ByteArray?
    private external fun nativeSetArtworkFingerprint(
        bitmap: Bitmap, depthBuffer: ByteBuffer?,
        depthW: Int, depthH: Int, depthStride: Int,
        intrinsics: FloatArray, viewMatrix: FloatArray
    )
    private external fun nativeSetRelocEnabled(enabled: Boolean)
    private external fun nativeSetSelfGrowEnabled(enabled: Boolean)
    private external fun nativeSetEvalRngSeed(seed: Long)
    private external fun nativeDetectSuperPoint(bitmap: Bitmap): FloatArray?
    private external fun nativeGetWallKeypointCount(): Int
    private external fun nativeSetWallPatch(bitmap: Bitmap)
    private external fun nativeSetWallPatchBytes(data: ByteArray, size: Int)
    private external fun nativeSetVoxelSize(size: Float)
    private external fun nativeSetParallaxMinDegrees(deg: Float)
    private external fun nativeSetMappingPaused(paused: Boolean)
    private external fun nativeFeedPointCloud(points: FloatArray)
    private external fun nativeFeedArCoreDepth(depthBuffer: ByteBuffer, width: Int, height: Int, rowStride: Int, intrinsics: FloatArray, intrW: Int, intrH: Int, cvRotateCode: Int, confidence: Float)
    private external fun nativeFeedYuvFrame(
        yBuffer: ByteBuffer,
        uBuffer: ByteBuffer,
        vBuffer: ByteBuffer,
        width: Int,
        height: Int,
        yStride: Int,
        uvStride: Int,
        uvPixelStride: Int,
        timestampNs: Long,
        cvRotateCode: Int
    )
    private external fun nativeFeedColorFrame(colorBuffer: ByteBuffer, width: Int, height: Int, timestampNs: Long, cvRotateCode: Int)
    private external fun nativeDestroy()
    private external fun nativeAnnotateKeypoints(bitmap: Bitmap)
    private external fun nativeFeedStereoData(leftBuffer: ByteBuffer, rightBuffer: ByteBuffer, width: Int, height: Int, timestamp: Long)

    private external fun nativePrepareLiquify(bitmap: Bitmap)
    private external fun nativeApplyLiquify(stroke: FloatArray, brushSize: Float, intensity: Float)
    private external fun nativeDrawLiquify(width: Int, height: Int)
    private external fun nativeBakeLiquify(outBitmap: Bitmap)

    private companion object {
        private const val TAG = "SlamManager"

        /**
         * How long a capture waits for the anchor to be established before giving up.
         *
         * The anchor is established on the GL frame after the artist confirms, so the realistic wait
         * is one frame — under 35 ms at 30 fps. Two seconds is a generous ceiling for a stalled or
         * dropped frame, not an expected duration, and timing out is a refusal rather than a
         * fallback. Recorded in `PARAMETERS.md` §6.
         */
        const val ANCHOR_WAIT_MS = 2_000L
    }
}
