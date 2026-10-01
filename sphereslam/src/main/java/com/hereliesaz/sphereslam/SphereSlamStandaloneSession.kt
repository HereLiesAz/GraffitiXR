package com.hereliesaz.sphereslam

import java.nio.ByteBuffer

/**
 * Synchronous wall-relative SphereSLAM/KPM session for the non-ARCore runtime.
 *
 * Unlike [SphereSlamTracker], this class is intended to be called from an existing camera-analysis
 * worker and returns the pose for that exact frame immediately. That makes visual-lock/loss state
 * explicit and lets the caller bridge a short miss with IMU orientation without an asynchronous
 * "latest observation" race.
 *
 * KPM supplies the 6-DoF camera-from-wall pose while a registered planar wall page is visible. This
 * is sufficient to drive GraffitiXR's wall-locked overlay without an ARCore Session. It is not
 * inertial dead reckoning and it does not claim a pose when the wall cannot be matched.
 */
class SphereSlamStandaloneSession(
    val frameWidth: Int,
    val frameHeight: Int,
    val calibration: SphereSlamCalibration,
    private val engineFactory: EngineFactory = EngineFactory { width, height, c ->
        SphereSlam.create(width, height, c)
    },
) : AutoCloseable {

    fun interface EngineFactory {
        fun create(
            width: Int,
            height: Int,
            calibration: SphereSlamCalibration,
        ): SphereSlamEngine
    }

    data class Reference(
        val pageNo: Int,
        val imageNo: Int,
        val geometry: SphereSlamPoseMath.PageGeometry,
        /**
         * Rigid transform from this centered page frame into the immutable canonical wall frame.
         * Page 0 uses identity; every grown page must be registered into that same root before it is
         * admitted to the atlas.
         */
        val canonicalFromPage: FloatArray,
        val featureCount: Int,
        /**
         * True only when the width supplied by the caller is an actual physical measurement.
         * False means the pose is internally scaled and visually stable, but distance values are not
         * physical metres.
         */
        val physicallyMetric: Boolean,
    )

    data class Pose(
        val timestampNs: Long,
        val pageNo: Int,
        val viewMatrix: FloatArray,
        val reprojectionError: Float,
        val inlierCount: Int,
        val reference: Reference,
    ) {
        init {
            require(viewMatrix.size == 16)
        }

        val physicallyMetric: Boolean get() = reference.physicallyMetric
    }

    private var engine: SphereSlamEngine = newEngine()
    private val references = linkedMapOf<Int, Reference>()
    private var closed = false

    val isReady: Boolean get() = !closed && engine.isReady
    val hasReference: Boolean get() = references.isNotEmpty()

    /**
     * Add a planar wall reference.
     *
     * @param referenceWidthMeters width represented by the whole reference image. Pass a measured
     * physical width and [physicallyMetric]=true for real metric translation. For the initial
     * standalone path, callers may use a normalized width such as 1f and keep
     * [physicallyMetric]=false; the overlay remains geometrically registered because reference
     * geometry and camera translation share the same scale.
     */
    fun addReference(
        luma: ByteBuffer,
        width: Int,
        height: Int,
        referenceWidthMeters: Float,
        physicallyMetric: Boolean,
        pageNo: Int = 0,
        imageNo: Int = 0,
        maxFeatures: Int = 5000,
        canonicalFromPage: FloatArray = SphereSlamPoseMath.identity4(),
    ): Reference {
        requireOpen()
        require(!references.containsKey(pageNo)) { "page $pageNo is already registered" }
        require(canonicalFromPage.size == 16 && canonicalFromPage.all { it.isFinite() }) {
            "canonicalFromPage must be a finite 4x4 transform"
        }
        val dpi = SphereSlamPoseMath.dpiForReferenceWidth(width, referenceWidthMeters)
        val geometry = SphereSlamPoseMath.pageGeometry(width, height, dpi)
        val featureCount = engine.addPage(
            luma,
            width,
            height,
            PlanarPage(
                pageNo = pageNo,
                imageNo = imageNo,
                referenceDpi = dpi,
                maxFeatures = maxFeatures,
            ),
        )
        return Reference(
            pageNo = pageNo,
            imageNo = imageNo,
            geometry = geometry,
            canonicalFromPage = canonicalFromPage.copyOf(),
            featureCount = featureCount,
            physicallyMetric = physicallyMetric,
        ).also { references[pageNo] = it }
    }

    /**
     * Match one tightly packed direct luma frame and return a centered OpenGL world-to-view pose.
     * Null means no wall page matched this frame.
     */
    fun match(luma: ByteBuffer, timestampNs: Long): Pose? {
        requireOpen()
        if (references.isEmpty()) return null
        val match = engine.match(luma) ?: return null
        val reference = references[match.pageNo] ?: return null
        val g = reference.geometry
        val cameraFromPage = SphereSlamPoseMath.pageToOpenGlViewMeters(
            match.cameraFromPage3x4,
            pageCenterXmm = g.centerXmm,
            pageCenterYmm = g.centerYmm,
        )
        return Pose(
            timestampNs = timestampNs,
            pageNo = match.pageNo,
            // Every KPM page owns a local centred coordinate system. Rebase that local view into
            // the one canonical wall frame before it escapes the session so renderer, MobileGS,
            // paint progress and persistence never observe a page-dependent coordinate jump.
            viewMatrix = SphereSlamPoseMath.pageViewToCanonicalView(
                cameraFromPage,
                reference.canonicalFromPage,
            ),
            reprojectionError = match.reprojectionError,
            inlierCount = match.inlierCount,
            reference = reference,
        )
    }

    /**
     * Drop the atlas and create a fresh native matcher with the same live-camera calibration.
     * Used when the artist captures a different wall.
     */
    fun reset() {
        requireOpen()
        engine.close()
        references.clear()
        engine = newEngine()
    }

    override fun close() {
        if (closed) return
        closed = true
        references.clear()
        engine.close()
    }

    private fun newEngine(): SphereSlamEngine {
        require(frameWidth > 0 && frameHeight > 0)
        return engineFactory.create(frameWidth, frameHeight, calibration)
    }

    private fun requireOpen() {
        check(!closed) { "standalone SphereSLAM session is closed" }
        check(engine.isReady) { "standalone SphereSLAM native engine is unavailable" }
    }
}
