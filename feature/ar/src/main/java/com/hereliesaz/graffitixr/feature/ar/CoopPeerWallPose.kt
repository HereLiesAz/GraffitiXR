package com.hereliesaz.graffitixr.feature.ar

import com.hereliesaz.graffitixr.common.model.CoopSpatialFrame
import com.hereliesaz.graffitixr.feature.ar.anchor.MetricMarks
import com.hereliesaz.graffitixr.feature.ar.anchor.PoseMath

internal data class CoopPeerWallPose(
    val viewMatrix: FloatArray,
    val inliers: Int,
    val matches: Int,
    val seq: Float,
)

/**
 * Turns MobileGS's peer-fingerprint PnP result into the one shared co-op wall view.
 *
 * Native PnP publishes CV camera-from-fingerprint. The v3 spatial descriptor supplies
 * fingerprint-from-host-wall. [MetricMarks.glViewToCv] is an involution (row Y/Z sign flip), so
 * applying it to the CV matrix produces the GL camera matrix used by the transparent renderer.
 */
internal object CoopPeerWallPoseSolver {
    const val MIN_INLIERS = 4
    const val MIN_INLIER_RATIO = 0.5f

    fun solve(reloc: FloatArray, spatialFrame: CoopSpatialFrame): CoopPeerWallPose? {
        if (reloc.size < 19) return null
        val seq = reloc[18]
        val inliers = reloc[16].toInt()
        val matches = reloc[17].toInt()
        if (!seq.isFinite() || seq <= 0f || matches <= 0 || inliers < MIN_INLIERS) return null
        if (inliers.toFloat() / matches.toFloat() < MIN_INLIER_RATIO) return null

        val pnpCv = reloc.copyOfRange(0, 16)
        if (pnpCv.any { !it.isFinite() }) return null
        val fingerprintFromWall = spatialFrame.fingerprintFromWall.toFloatArray()
        val cameraGlFromFingerprint = MetricMarks.glViewToCv(pnpCv)
        val cameraGlFromWall = PoseMath.multiply(cameraGlFromFingerprint, fingerprintFromWall)
        if (cameraGlFromWall.any { !it.isFinite() }) return null
        return CoopPeerWallPose(cameraGlFromWall, inliers, matches, seq)
    }
}
