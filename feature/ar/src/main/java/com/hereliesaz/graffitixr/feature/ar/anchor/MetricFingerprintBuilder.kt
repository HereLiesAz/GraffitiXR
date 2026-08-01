package com.hereliesaz.graffitixr.feature.ar.anchor

import android.graphics.Bitmap
import android.opengl.Matrix
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

            // Center the AR overlay on the marks (their centroid), not the screen-center anchor.
            val markCenterLocal = marksCentroidLocal(tri.pointsCam0, cv0, anchorModel)
            slam.overlayMarkCenterLocal = markCenterLocal

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
            return Fingerprint(
                keypoints, tri.pointsCam0.toList(), bytes, rows, cols, type,
                markCenterLocal = markCenterLocal?.toList() ?: emptyList(),
            )
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

    /**
     * Single-capture path: build the wall fingerprint from ONE view by back-projecting detected
     * features onto a known wall plane (the green ARCore plane, whose metric pose ARCore has already
     * solved). No triangulation, no second view. Detects SuperPoint first (CV_32F → reloc runs its
     * SuperPoint path), falls back to ORB. Returns null if too few features hit the plane.
     *
     * @param glView the capture's ARCore/GL world→camera matrix.
     * @param planePointWorld a point on the wall plane in world space (e.g. plane centre).
     * @param planeNormalWorld the wall plane normal in world space.
     * @param anchorModel the anchor's world pose (column-major 4x4) at capture time.
     */
    /**
     * How a [buildSingle] attempt went, so a failure can say what was actually short instead of a
     * generic "not enough texture". Reset at the start of each attempt.
     *
     * [detected] is how many features the detector found in the frame at all; [placed] is how many of
     * those back-projected onto the wall plane. detected≈0 means the frame had no texture to work
     * with (light, focus, blur, a blank wall). detected high but placed low means the features were
     * there but missed the plane — usually aiming past the plane's extent, or a plane whose polygon
     * doesn't cover what the camera sees.
     */
    @Volatile var lastDetected: Int = 0
        private set
    @Volatile var lastPlaced: Int = 0
        private set
    @Volatile var lastRequired: Int = 0
        private set

    /**
     * How many of the placed points landed in the backbone set `F_out`, or -1 when the capture was
     * not partitioned at all (no design footprint supplied — the legacy all-backbone case).
     *
     * -1 rather than 0 for the reason `EVALUATION.md` records twice over: 0 is a *measurement*, and
     * "the design covers the whole wall so there is no backbone" and "nobody asked for a partition"
     * are opposite situations that must not print the same number.
     */
    @Volatile var lastBackbone: Int = -1
        private set

    /**
     * Minimum backbone size for a capture to be worth keeping (`IMPLEMENTATION.md` 2.8).
     *
     * `F_out` is what the reloc PnP solves from with no prior. If the design covers the whole
     * visible wall there is nothing outside it, and the target will never lock — the paper states
     * this as a limit of the domain of applicability, so the implementation's job is to say so at
     * capture rather than let the artist discover it as mysterious tracking failure an hour later.
     *
     * `40` is the pre-registered prior from `PARAMETERS.md` §4 (`BACKBONE_MIN_FEATURES`): 5× the
     * native PnP correspondence floor of 8, leaving room for the fact that only a fraction of stored
     * features match in any one live frame. It is a *prior*, not a measurement — `EVALUATION.md` E8
     * is the experiment that sets it, and both failure modes are real: too high refuses captures
     * that would have worked, too low ships a target that can never lock.
     *
     * Deliberately NOT tied to `minPoints`' default of 20. The two floors ask different questions —
     * "did enough features land on the wall at all" versus "is enough of what landed outside the
     * artwork" — and a capture can comfortably pass the first while failing the second, which is
     * exactly the design-covers-the-whole-wall case this exists to catch.
     */
    const val MIN_BACKBONE = 40

    /**
     * @param rotationDeg `rotationNeeded` for this capture — the angle the caller already applied
     *   to [bitmap] and to [intr]. The view is rotated to match (`IMPLEMENTATION.md` Phase 0,
     *   convention B); passing 0 while handing in rotated pixels reinstates the `PAPER.md` §8
     *   defect, which is why it has no default.
     * @param design where the artwork sits at capture, in world space (`IMPLEMENTATION.md` 2.3/2.4).
     *   Null — the default — leaves the fingerprint unpartitioned, which every consumer reads as
     *   all-backbone and is byte-for-byte the behaviour before Phase 2. That is a real default, not
     *   a shortcut: the depth path and the doodle path have no placed design to speak of.
     */
    fun buildSingle(
        slam: SlamManager,
        bitmap: Bitmap, glView: FloatArray, intr: FloatArray,
        planePointWorld: FloatArray, planeNormalWorld: FloatArray,
        anchorModel: FloatArray,
        rotationDeg: Int,
        minPoints: Int = 20,
        design: FingerprintPartition.DesignFootprint? = null,
    ): Fingerprint? {
        lastDetected = 0; lastPlaced = 0; lastRequired = minPoints; lastBackbone = -1
        // Convention B: the bitmap and intrinsics arrived in display orientation, so the view goes
        // there too. This is the fix for PAPER.md §8 — previously `glViewToCv(glView)`, which left
        // display-frame rays intersecting a sensor-frame plane.
        val cvView = MetricMarks.glViewToCvDisplay(glView, rotationDeg)

        val sp = unpackSuperPoint(slam.detectSuperPoint(bitmap))
        if (sp != null) {
            val (pos, descs) = sp
            try {
                val pixels = ArrayList<PlaneMarks.Pixel>(pos.size / 2)
                var i = 0
                while (i + 1 < pos.size) { pixels.add(PlaneMarks.Pixel(pos[i], pos[i + 1])); i += 2 }
                val fp = ingestSingle(slam, descs, pixels, cvView, intr,
                    planePointWorld, planeNormalWorld, anchorModel, minPoints, glView, rotationDeg,
                    design)
                if (fp != null) return fp
            } finally {
                descs.release()
            }
        }

        // ORB fallback — CLAHE-normalize identically to the native reloc path so descriptors match.
        val gray = toGray(bitmap)
        val orb = ORB.create(1500)
        val clahe = Imgproc.createCLAHE(2.0, org.opencv.core.Size(8.0, 8.0))
        val norm = Mat()
        val kp = MatOfKeyPoint(); val d = Mat()
        try {
            clahe.apply(gray, norm)
            orb.detectAndCompute(norm, Mat(), kp, d)
            if (d.empty()) return null
            val pixels = kp.toArray().map { PlaneMarks.Pixel(it.pt.x.toFloat(), it.pt.y.toFloat()) }
            return ingestSingle(slam, d, pixels, cvView, intr,
                planePointWorld, planeNormalWorld, anchorModel, minPoints, glView, rotationDeg,
                design)
        } finally {
            gray.release(); norm.release(); kp.release(); d.release()
        }
    }

    /** Back-project the detected pixels onto the plane, keep the descriptors that hit, ingest. */
    private fun ingestSingle(
        slam: SlamManager,
        descAll: Mat,
        pixels: List<PlaneMarks.Pixel>,
        cvView: FloatArray, intr: FloatArray,
        planePointWorld: FloatArray, planeNormalWorld: FloatArray,
        anchorModel: FloatArray, minPoints: Int,
        // GL-convention capture view, handed to native so the reloc thread can pre-cancel
        // oblique-vs-frontal distortion. Null on the two-keyframe path, which has no single view.
        glView: FloatArray? = null,
        /** rotationNeeded for this capture; rotates [glView] to match the points. See 0.10. */
        rotationDeg: Int = 0,
        design: FingerprintPartition.DesignFootprint? = null,
    ): Fingerprint? {
        val res = PlaneMarks.backProject(
            pixels, cvView, planePointWorld, planeNormalWorld,
            intr[0], intr[1], intr[2], intr[3],
        )
        // Record before the bail so a refusal can report how far short it fell. SuperPoint runs first
        // and ORB is the fallback, so keep whichever pass got furthest rather than the last one.
        if (pixels.size > lastDetected) lastDetected = pixels.size
        if (res.count > lastPlaced) lastPlaced = res.count
        if (res.count < minPoints) return null

        // IMPLEMENTATION.md 2.4 — tag each surviving point with its region relative to the design's
        // projected footprint. `res.pointsCam` is in the capture CV camera frame and the renderer's
        // design pose is in world, so the design is moved into the points' frame ONCE here rather
        // than every point being moved into the design's; see DesignFootprint.inFrameOf for why the
        // two are equivalent. A null or degenerate design leaves regions empty, which every consumer
        // reads as all-backbone — Phase 2's documented rollback, reached by doing nothing.
        val designInPointFrame = design?.takeIf { it.isUsable }?.inFrameOf(cvView)
        val regions = designInPointFrame?.let {
            FingerprintPartition.classify(res.pointsCam, it.rigidModel, it.halfW, it.halfH)
        } ?: ByteArray(0)

        // IMPLEMENTATION.md 2.8 — refuse a capture with no backbone to localize against. Checked
        // before anything is published or committed, so a refusal leaves no half-built target and no
        // stale overlay re-centering behind. lastBackbone stays -1 when unpartitioned: not measured,
        // not zero.
        if (regions.isNotEmpty()) {
            lastBackbone = FingerprintPartition.backboneCount(regions)
            if (lastBackbone < MIN_BACKBONE) return null
        }

        // Center the AR overlay on the marks (their centroid), not the screen-center anchor.
        val markCenterLocal = marksCentroidLocal(res.pointsCam, cvView, anchorModel)
        slam.overlayMarkCenterLocal = markCenterLocal

        val keptDesc = Mat(res.count, descAll.cols(), descAll.type())
        val keypoints = ArrayList<KeyPoint>(res.count)
        for ((dst, src) in res.kept.withIndex()) {
            descAll.row(src).copyTo(keptDesc.row(dst))
            val px = pixels[src]
            keypoints.add(KeyPoint(px.u, px.v, 7f))
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

        // IMPLEMENTATION.md 0.10 — the stored capture view must be in the SAME frame as
        // res.pointsCam, which glViewToCvDisplay has already moved to display orientation.
        // Native pairs the two in computeRectifyHomography (viewFp / mWallKeypoints3D) against a
        // display-oriented viewCur, so handing it the raw sensor-frame view leaves the rectifying
        // homography rotated by R_z. Note the GL sign is NEGATED — see glViewDisplayOriented.
        slam.restoreWallFingerprintMetric(
            bytes, rows, cols, type, res.pointsCam, anchorModel, intr,
            viewMatrix = glView?.let { MetricMarks.glViewDisplayOriented(it, rotationDeg) }
                ?: FloatArray(0),
            // IMPLEMENTATION.md 2.6 — native filters the reloc correspondence build by these, and
            // reads a zero-length array as all-backbone, so an unpartitioned capture behaves as it
            // did before Phase 2 rather than losing its whole map.
            regions = regions,
        )
        return Fingerprint(
            keypoints, res.pointsCam.toList(), bytes, rows, cols, type,
            markCenterLocal = markCenterLocal?.toList() ?: emptyList(),
            // IMPLEMENTATION.md 0.7 — record the frame these points were built in, so a reload can
            // tell a Phase-0 fingerprint from a pre-Phase-0 one instead of guessing.
            captureRotationDeg = rotationDeg,
            // IMPLEMENTATION.md 2.4 — the partition, and the design placement that defines it. The
            // model is stored in the POINTS' frame (see DesignFootprint.inFrameOf) so a later
            // reclassify after a project reload has a frame it can trust.
            regions = regions,
            captureHalfW = designInPointFrame?.halfW ?: -1f,
            captureHalfH = designInPointFrame?.halfH ?: -1f,
            captureDesignModel = designInPointFrame?.rigidModel?.toList() ?: emptyList(),
        )
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

    /**
     * Centroid [x,y,z] of the kept 3D marks expressed in the FINGERPRINT ANCHOR's local frame, used
     * to center the AR overlay on the marks rather than the screen-center anchor. [pointsCam] are in
     * the capture's CV camera frame (flat [x,y,z,...]): invert the CV world→camera view to get their
     * mean in world space, then invert [anchorModel] (the anchor's world pose) to express it in the
     * anchor's frame — which survives a reload into a new ARCore session. Returns null if there are
     * no points or either matrix isn't invertible.
     */
    private fun marksCentroidLocal(pointsCam: FloatArray, cvView: FloatArray, anchorModel: FloatArray): FloatArray? {
        val n = pointsCam.size / 3
        if (n == 0) return null
        var sx = 0f; var sy = 0f; var sz = 0f
        for (i in 0 until n) { sx += pointsCam[i * 3]; sy += pointsCam[i * 3 + 1]; sz += pointsCam[i * 3 + 2] }
        val cam = floatArrayOf(sx / n, sy / n, sz / n, 1f)
        val invView = FloatArray(16)
        if (!Matrix.invertM(invView, 0, cvView, 0)) return null
        val world = FloatArray(4)
        Matrix.multiplyMV(world, 0, invView, 0, cam, 0)
        val invAnchor = FloatArray(16)
        if (!Matrix.invertM(invAnchor, 0, anchorModel, 0)) return null
        val local = FloatArray(4)
        Matrix.multiplyMV(local, 0, invAnchor, 0, world, 0)
        return floatArrayOf(local[0], local[1], local[2])
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
