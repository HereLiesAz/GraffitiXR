package com.hereliesaz.graffitixr.feature.ar

import com.hereliesaz.graffitixr.common.model.CoopSpatialFrame
import com.hereliesaz.graffitixr.common.model.CoopSpatialScale
import com.hereliesaz.graffitixr.common.model.CoopTrackingBackend
import com.hereliesaz.graffitixr.common.model.Fingerprint
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.common.model.SPHERE_SLAM_FINGERPRINT_FRAME_VERSION

/**
 * Builds the durable host wall-frame descriptor used by co-op protocol v3.
 *
 * Nothing here consumes a device world origin. ARCore contributes the capture-camera-from-anchor
 * bridge already persisted on [Fingerprint.captureAnchorCam]; standalone contributes identity
 * because its fingerprint object points already live in the centered KPM wall frame.
 */
internal object CoopSpatialFrameFactory {
    private const val ARCORE_FINGERPRINT_FRAME_VERSION = 1

    fun localBackend(arCoreAvailable: Boolean): CoopTrackingBackend =
        if (arCoreAvailable) CoopTrackingBackend.ARCORE else CoopTrackingBackend.SPHERESLAM

    fun fromProject(project: GraffitiProject, arCoreAvailable: Boolean): CoopSpatialFrame? =
        if (arCoreAvailable) fromArCoreProject(project) else fromSphereSlamProject(project)

    private fun fromSphereSlamProject(project: GraffitiProject): CoopSpatialFrame? {
        if (project.sphereSlamReferenceUri == null) return null
        if (
            project.sphereSlamAnchorFrameVersion != SPHERE_SLAM_FINGERPRINT_FRAME_VERSION ||
            project.sphereSlamReferenceWidthMeters <= 0f ||
            !project.sphereSlamReferenceWidthMeters.isFinite()
        ) return null

        val fp = project.sphereSlamFingerprint
        val fingerprintAvailable =
            fp != null &&
                project.sphereSlamFingerprintFrameVersion == SPHERE_SLAM_FINGERPRINT_FRAME_VERSION &&
                fp.descriptorsRows > 0 &&
                fp.points3d.size == fp.descriptorsRows * 3

        return CoopSpatialFrame(
            hostBackend = CoopTrackingBackend.SPHERESLAM,
            fingerprintFrameVersion = SPHERE_SLAM_FINGERPRINT_FRAME_VERSION,
            scale = if (project.sphereSlamReferencePhysicallyMetric) {
                CoopSpatialScale.METRIC
            } else {
                CoopSpatialScale.NORMALIZED_PAGE
            },
            fingerprintAvailable = fingerprintAvailable,
            anchorRevision = project.sphereSlamAnchorGeneration,
            referenceWidthUnits = project.sphereSlamReferenceWidthMeters,
            fingerprintFromWall = identity(),
        )
    }

    private fun fromArCoreProject(project: GraffitiProject): CoopSpatialFrame? {
        val fp = project.fingerprint ?: return null
        if (
            fp.descriptorsRows <= 0 ||
            fp.points3d.size != fp.descriptorsRows * 3 ||
            fp.captureAnchorCam.size != 16 ||
            fp.captureAnchorCam.any { !it.isFinite() } ||
            fp.isLegacyFrame()
        ) return null

        return CoopSpatialFrame(
            hostBackend = CoopTrackingBackend.ARCORE,
            fingerprintFrameVersion = ARCORE_FINGERPRINT_FRAME_VERSION,
            scale = CoopSpatialScale.METRIC,
            fingerprintAvailable = true,
            anchorRevision = fingerprintRevision(fp),
            referenceWidthUnits =
                (project.arDesignHalfWidthM.takeIf { it.isFinite() && it > 0f }?.times(2f) ?: 1f),
            fingerprintFromWall = fp.captureAnchorCam,
        )
    }

    /**
     * Stable content revision for ARCore fingerprints, which predate the explicit standalone-style
     * anchor generation. It deliberately hashes only geometry/descriptor/frame identity, not design
     * edits or lastModified, so painting/editing does not tear down a healthy co-op session.
     */
    internal fun fingerprintRevision(fp: Fingerprint): Long {
        var h = -3750763034362895579L // 64-bit FNV offset, represented as signed Long.
        fun byte(v: Int) {
            h = h xor (v and 0xff).toLong()
            h *= 1099511628211L
        }
        fun int(v: Int) {
            byte(v)
            byte(v ushr 8)
            byte(v ushr 16)
            byte(v ushr 24)
        }
        int(fp.descriptorsRows)
        int(fp.descriptorsCols)
        int(fp.descriptorsType)
        int(fp.captureRotationDeg)
        fp.descriptorsData.forEach { byte(it.toInt()) }
        fp.points3d.forEach { int(java.lang.Float.floatToIntBits(it)) }
        fp.captureAnchorCam.forEach { int(java.lang.Float.floatToIntBits(it)) }
        return h
    }

    private fun identity(): List<Float> = listOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )
}
