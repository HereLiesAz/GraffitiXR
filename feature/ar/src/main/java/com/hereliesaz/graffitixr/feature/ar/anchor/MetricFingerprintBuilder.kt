package com.hereliesaz.graffitixr.feature.ar.anchor

import com.hereliesaz.graffitixr.nativebridge.SlamManager
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.ORB

/**
 * Builds a wall fingerprint from two camera keyframes by triangulation — the depth-off path. Detects
 * ORB on both views, matches with a Lowe ratio test, triangulates the matches into metric 3D via
 * [MetricMarks] (which wraps the tested [Triangulation] core), and hands the result to the native
 * engine through [SlamManager.restoreWallFingerprintMetric].
 *
 * The artist's natural step-in / step-back supplies the baseline; no second lens, no ML depth. Points
 * land in keyframe-0's CV camera frame with the anchor pose attached, so they relocalize through the
 * same PnP/PoseFusion path as a depth-built fingerprint.
 *
 * ORB (CV_8U) descriptors are used deliberately: the native reloc thread only takes the SuperPoint
 * path when the stored descriptors are CV_32F, so CV_8U keeps both ends on ORB and consistent.
 */
object MetricFingerprintBuilder {

    /** One captured view: grayscale image, its ARCore/GL world→camera view, and pinhole intrinsics. */
    class Keyframe(
        val gray: Mat,
        val glView: FloatArray,
        val fx: Float, val fy: Float, val cx: Float, val cy: Float,
    )

    /**
     * Detect, match, triangulate, and ingest. Returns the number of metric points committed (0 if the
     * geometry was too weak — too few matches survived triangulation/reprojection filtering).
     *
     * @param anchorModel the anchor's world pose (column-major 4x4) at keyframe-0's time.
     */
    fun build(
        slam: SlamManager,
        kf0: Keyframe,
        kf1: Keyframe,
        anchorModel: FloatArray,
        minPoints: Int = 20,
        ratio: Float = 0.75f,
    ): Int {
        val matched = detectAndMatch(kf0.gray, kf1.gray, ratio) ?: return 0
        val cv0 = MetricMarks.glViewToCv(kf0.glView)
        val cv1 = MetricMarks.glViewToCv(kf1.glView)
        val tri = MetricMarks.triangulate(matched.corrs, cv0, cv1, kf0.fx, kf0.fy, kf0.cx, kf0.cy)
        if (tri.count < minPoints) return 0

        // Keep only the rows of frame-0's descriptors whose match survived triangulation.
        val keptDesc = Mat(tri.count, matched.descriptors.cols(), matched.descriptors.type())
        for ((dst, src) in tri.kept.withIndex()) {
            matched.descriptors.row(src).copyTo(keptDesc.row(dst))
        }
        val bytes = ByteArray(keptDesc.rows() * keptDesc.cols() * keptDesc.elemSize().toInt())
        keptDesc.get(0, 0, bytes)

        slam.restoreWallFingerprintMetric(
            bytes, keptDesc.rows(), keptDesc.cols(), keptDesc.type(),
            tri.pointsCam0, anchorModel,
            floatArrayOf(kf0.fx, kf0.fy, kf0.cx, kf0.cy),
        )
        keptDesc.release()
        return tri.count
    }

    private class Matched(val corrs: List<MetricMarks.Corr>, val descriptors: Mat)

    /** ORB detect + ratio-tested BF-Hamming match. descriptors[i] is frame-0's descriptor for corrs[i]. */
    private fun detectAndMatch(gray0: Mat, gray1: Mat, ratio: Float): Matched? {
        val orb = ORB.create(1500)
        val kp0 = MatOfKeyPoint(); val kp1 = MatOfKeyPoint()
        val d0 = Mat(); val d1 = Mat()
        try {
            orb.detectAndCompute(gray0, Mat(), kp0, d0)
            orb.detectAndCompute(gray1, Mat(), kp1, d1)
            if (d0.empty() || d1.empty()) return null

            val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)
            val knn = ArrayList<MatOfDMatch>()
            matcher.knnMatch(d0, d1, knn, 2)

            val pts0 = kp0.toArray()
            val pts1 = kp1.toArray()
            val corrs = ArrayList<MetricMarks.Corr>(knn.size)
            val rows = ArrayList<Int>(knn.size)
            for (mm in knn) {
                val m = mm.toArray()
                if (m.size < 2) continue
                if (m[0].distance < ratio * m[1].distance) {
                    val p0 = pts0[m[0].queryIdx].pt
                    val p1 = pts1[m[0].trainIdx].pt
                    corrs.add(MetricMarks.Corr(p0.x.toFloat(), p0.y.toFloat(), p1.x.toFloat(), p1.y.toFloat()))
                    rows.add(m[0].queryIdx)
                }
            }
            if (corrs.isEmpty()) return null

            val desc = Mat(corrs.size, d0.cols(), d0.type())
            for ((dst, src) in rows.withIndex()) d0.row(src).copyTo(desc.row(dst))
            return Matched(corrs, desc)
        } finally {
            kp0.release(); kp1.release(); d0.release(); d1.release()
        }
    }
}
</content>
