// FILE: core/nativebridge/src/main/java/com/hereliesaz/graffitixr/nativebridge/SlamManager.kt
package com.hereliesaz.graffitixr.nativebridge

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.util.Log
import com.hereliesaz.graffitixr.common.model.Fingerprint
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

    fun updateAnchorTransform(transform: FloatArray) = nativeUpdateAnchorTransform(transform)

    fun updateDeviceMotion(angularVel: FloatArray, linearVel: FloatArray) {
        nativeUpdateDeviceMotion(angularVel, linearVel)
    }

    fun getPaintingProgress(): Float = nativeGetPaintingProgress()

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
     */
    fun restoreWallFingerprintMetric(
        descriptorsData: ByteArray, rows: Int, cols: Int, type: Int,
        points3d: FloatArray, anchorMatrix: FloatArray, intrinsics: FloatArray,
    ) {
        nativeRestoreWallFingerprintMetric(descriptorsData, rows, cols, type, points3d, anchorMatrix, intrinsics)
    }

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

    fun setArScanMode(mode: Int) = nativeSetArScanMode(mode)
    fun setMuralMethod(method: Int) = nativeSetMuralMethod(method)

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
    private external fun nativeSetArScanMode(mode: Int)
    private external fun nativeSetMuralMethod(method: Int)
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
        points3d: FloatArray, anchorMatrix: FloatArray, intrinsics: FloatArray
    )
    private external fun nativeRestoreWallFeatureMap(
        descriptorsData: ByteArray, rows: Int, cols: Int, type: Int,
        points3d: FloatArray, confidence: FloatArray, obsCount: IntArray,
        anchor: FloatArray, intrinsics: FloatArray
    )
    private external fun nativeClearWallFeatureMap()
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
    }
}
