package com.hereliesaz.graffitixr.feature.ar.anchor

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.model.Fingerprint
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import org.opencv.android.Utils
import org.opencv.core.KeyPoint
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.features.DescriptorMatcher
import org.opencv.features.ORB
import org.opencv.imgproc.Imgproc

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
     * Detect, match, triangulate, and ingest. Returns a persistable [Fingerprint] (descriptors +
     * keyframe-0-frame 3D points), or null if the geometry was too weak — too few matches survived
     * triangulation/reprojection filtering. The result is also committed to the live native engine.
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
    ): Fingerprint? {
        val matched = detectAndMatch(kf0.gray, kf1.gray, ratio) ?: return null
        return assemble(slam, matched, kf0.glView, kf1.glView,
            floatArrayOf(kf0.fx, kf0.fy, kf0.cx, kf0.cy), anchorModel, minPoints)
    }

    /** Triangulate a Matched set into metric 3D, ingest it, and return the persistable Fingerprint. */
    private fun assemble(
        slam: SlamManager, matched: Matched,
        glView0: FloatArray, glView1: FloatArray, intr0: FloatArray,
        anchorModel: FloatArray, minPoints: Int,
    ): Fingerprint? {
        try {
            val cv0 = MetricMarks.glViewToCv(glView0)
            val cv1 = MetricMarks.glViewToCv(glView1)
            val tri = MetricMarks.triangulate(matched.corrs, cv0, cv1, intr0[0], intr0[1], intr0[2], intr0[3])
            if (tri.count < minPoints) return null

            // Keep the rows of frame-0's descriptors (and the frame-0 keypoints) whose match survived.
            val keptDesc = Mat(tri.count, matched.descriptors.cols(), matched.descriptors.type())
            val keypoints = ArrayList<KeyPoint>(tri.count)
            for ((dst, src) in tri.kept.withIndex()) {
                matched.descriptors.row(src).copyTo(keptDesc.row(dst))
                val c = matched.corrs[src]
                keypoints.add(KeyPoint(c.u0, c.v0, 7f))
            }
            val type = keptDesc.type()
            val rows = keptDesc.rows(); val cols = keptDesc.cols()
            val bytes: ByteArray
            if (type == org.opencv.core.CvType.CV_32F) {
                val floats = FloatArray(rows * cols)
                keptDesc.get(0, 0, floats)
                val buffer = java.nio.ByteBuffer.allocate(floats.size * 4).order(java.nio.ByteOrder.nativeOrder())
                buffer.asFloatBuffer().put(floats)
                bytes = buffer.array()
            } else {
                bytes = ByteArray(rows * cols * keptDesc.elemSize().toInt())
                keptDesc.get(0, 0, bytes)
            }
            keptDesc.release()

            slam.restoreWallFingerprintMetric(
                bytes, rows, cols, type, tri.pointsCam0, anchorModel, intr0,
            )
            return Fingerprint(keypoints, tri.pointsCam0.toList(), bytes, rows, cols, type)
        } finally {
            matched.descriptors.release()
        }
    }

    /**
     * Bitmap convenience overload: converts each view to grayscale and builds. The two intrinsics
     * (fx,fy,cx,cy) must describe the same pixel frame as the bitmaps; pass the two views' ARCore
     * world→camera matrices and the anchor's world pose at keyframe-0's time.
     */
    fun build(
        slam: SlamManager,
        bitmap0: Bitmap, glView0: FloatArray, intr0: FloatArray,
        bitmap1: Bitmap, glView1: FloatArray, intr1: FloatArray,
        anchorModel: FloatArray,
        minPoints: Int = 20,
    ): Fingerprint? {
        // Prefer SuperPoint (learned, far more robust to viewpoint/illumination) so the depth-off
        // fingerprint is CV_32F and the reloc thread runs its SuperPoint path. Falls back to ORB if the
        // model isn't loaded or the SuperPoint match/triangulation is too weak — strictly never-worse.
        val sp = matchSuperPoint(slam, bitmap0, bitmap1, 0.75f)
        if (sp != null) {
            val fp = assemble(slam, sp, glView0, glView1, intr0, anchorModel, minPoints)
            if (fp != null) return fp
        }

        val gray0 = toGray(bitmap0)
        val gray1 = toGray(bitmap1)
        try {
            val kf0 = Keyframe(gray0, glView0, intr0[0], intr0[1], intr0[2], intr0[3])
            val kf1 = Keyframe(gray1, glView1, intr1[0], intr1[1], intr1[2], intr1[3])
            return build(slam, kf0, kf1, anchorModel, minPoints)
        } finally {
            gray0.release(); gray1.release()
        }
    }

    /** SuperPoint detect (native, CLAHE'd) on both views + L2 ratio match → Matched (CV_32F descs). */
    private fun matchSuperPoint(slam: SlamManager, bitmap0: Bitmap, bitmap1: Bitmap, ratio: Float): Matched? {
        val sp0 = unpackSuperPoint(slam.detectSuperPoint(bitmap0)) ?: return null
        val sp1 = unpackSuperPoint(slam.detectSuperPoint(bitmap1)) ?: return null
        val (pos0, d0) = sp0
        val (pos1, d1) = sp1
        try {
            val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE) // L2 for float descriptors
            val knn = ArrayList<MatOfDMatch>()
            matcher.knnMatch(d0, d1, knn, 2)
            val corrs = ArrayList<MetricMarks.Corr>(knn.size)
            val rows = ArrayList<Int>(knn.size)
            for (mm in knn) {
                val m = mm.toArray()
                if (m.size < 2) continue
                if (m[0].distance < ratio * m[1].distance) {
                    val q = m[0].queryIdx; val t = m[0].trainIdx
                    corrs.add(MetricMarks.Corr(pos0[2 * q], pos0[2 * q + 1], pos1[2 * t], pos1[2 * t + 1]))
                    rows.add(q)
                }
            }
            if (corrs.isEmpty()) return null
            val desc = Mat(corrs.size, d0.cols(), d0.type())
            for ((dst, src) in rows.withIndex()) d0.row(src).copyTo(desc.row(dst))
            return Matched(corrs, desc)
        } finally {
            d0.release(); d1.release()
        }
    }

    /** Unpack the native [n, dim, (u,v)*n, descriptors] array into (positions, CV_32F descriptor Mat). */
    private fun unpackSuperPoint(raw: FloatArray?): Pair<FloatArray, Mat>? {
        if (raw == null || raw.size < 2) return null
        val n = raw[0].toInt(); val d = raw[1].toInt()
        if (n <= 0 || d <= 0 || raw.size < 2 + 2 * n + n * d) return null
        val pos = raw.copyOfRange(2, 2 + 2 * n)
        val descs = Mat(n, d, org.opencv.core.CvType.CV_32F)
        descs.put(0, 0, raw.copyOfRange(2 + 2 * n, 2 + 2 * n + n * d))
        return Pair(pos, descs)
    }

    private fun toGray(bitmap: Bitmap): Mat {
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()
        return gray
    }

    private class Matched(val corrs: List<MetricMarks.Corr>, val descriptors: Mat)

    /** ORB detect + ratio-tested BF-Hamming match. descriptors[i] is frame-0's descriptor for corrs[i]. */
    private fun detectAndMatch(gray0: Mat, gray1: Mat, ratio: Float): Matched? {
        val orb = ORB.create(1500)
        // Illumination-normalize identically to the native reloc path (CLAHE 2.0 / 8x8) so this
        // triangulated fingerprint's descriptors match the live frame's under light/color changes.
        // CLAHE doesn't move pixels, so keypoint positions (used for triangulation) are unaffected.
        val clahe = Imgproc.createCLAHE(2.0, org.opencv.core.Size(8.0, 8.0))
        val n0 = Mat(); val n1 = Mat()
        clahe.apply(gray0, n0); clahe.apply(gray1, n1)
        val kp0 = MatOfKeyPoint(); val kp1 = MatOfKeyPoint()
        val d0 = Mat(); val d1 = Mat()
        try {
            orb.detectAndCompute(n0, Mat(), kp0, d0)
            orb.detectAndCompute(n1, Mat(), kp1, d1)
            n0.release(); n1.release()
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
