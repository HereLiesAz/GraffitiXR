package com.hereliesaz.graffitixr.feature.ar.anchor

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.model.Fingerprint
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import com.hereliesaz.sphereslam.SphereSlamPoseMath
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.KeyPoint
import org.opencv.core.Mat
import org.opencv.core.MatOfKeyPoint
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc

/**
 * Builds the standalone MobileGS fingerprint directly from the rectified SphereSLAM wall page.
 *
 * Unlike [MetricFingerprintBuilder], no ARCore world/capture-camera frame is involved. Every
 * descriptor row is paired with a point on z=0 in [StandaloneFingerprintFrame], which is the same
 * centered wall frame KPM and the standalone renderer use.
 *
 * SuperPoint is preferred when the native model is loaded because the live MobileGS relocalizer
 * will then use its CV_32F SuperPoint path. ORB(1500) + CLAHE is the fallback and intentionally
 * matches MobileGS::normalizeForFeatures + mFeatureDetector.
 */
object StandaloneFingerprintBuilder {
    private const val DEFAULT_MIN_POINTS = 16
    private const val ORB_FEATURES = 1500

    internal data class DetectedFeatures(
        val positions: FloatArray, // [u0,v0,u1,v1,...]
        val descriptorsData: ByteArray,
        val rows: Int,
        val cols: Int,
        val type: Int,
    ) {
        init {
            require(rows >= 0 && cols >= 0)
            require(positions.size == rows * 2)
            if (rows > 0) {
                require(descriptorsData.size % rows == 0)
            }
        }
    }

    fun build(
        slam: SlamManager,
        bitmap: Bitmap,
        referenceWidthMeters: Float,
        minPoints: Int = DEFAULT_MIN_POINTS,
    ): Fingerprint? {
        require(bitmap.width > 0 && bitmap.height > 0)
        require(referenceWidthMeters.isFinite() && referenceWidthMeters > 0f)
        require(minPoints >= 8)

        val dpi = SphereSlamPoseMath.dpiForReferenceWidth(bitmap.width, referenceWidthMeters)
        val geometry = SphereSlamPoseMath.pageGeometry(bitmap.width, bitmap.height, dpi)

        val detected = detectSuperPoint(slam, bitmap)
            ?.takeIf { it.rows >= minPoints }
            ?: detectOrb(bitmap)?.takeIf { it.rows >= minPoints }
            ?: return null

        return assemble(
            detected = detected,
            widthPixels = bitmap.width,
            heightPixels = bitmap.height,
            geometry = geometry,
            minPoints = minPoints,
        )
    }

    /**
     * Pure geometry/row-alignment seam used by JVM tests.
     *
     * Invalid detector coordinates are removed together with their descriptor row; they can never
     * create a descriptor/object-point indexing mismatch in native PnP.
     */
    internal fun assemble(
        detected: DetectedFeatures,
        widthPixels: Int,
        heightPixels: Int,
        geometry: SphereSlamPoseMath.PageGeometry,
        minPoints: Int = DEFAULT_MIN_POINTS,
    ): Fingerprint? {
        require(widthPixels > 0 && heightPixels > 0)
        require(minPoints >= 8)
        if (detected.rows == 0 || detected.cols == 0) return null

        val rowBytes = detected.descriptorsData.size / detected.rows
        if (rowBytes <= 0) return null

        val keep = ArrayList<Int>(detected.rows)
        val points = ArrayList<Float>(detected.rows * 3)
        val keypoints = ArrayList<KeyPoint>(detected.rows)
        var sx = 0f
        var sy = 0f

        for (row in 0 until detected.rows) {
            val u = detected.positions[row * 2]
            val v = detected.positions[row * 2 + 1]
            if (!u.isFinite() || !v.isFinite()) continue
            if (u < 0f || u >= widthPixels.toFloat() || v < 0f || v >= heightPixels.toFloat()) {
                continue
            }
            val p = StandaloneFingerprintFrame.referencePixelToWall(
                u = u,
                v = v,
                widthPixels = widthPixels,
                heightPixels = heightPixels,
                geometry = geometry,
            )
            keep += row
            points += p.x
            points += p.y
            points += p.z
            sx += p.x
            sy += p.y
            keypoints += KeyPoint(u, v, 7f)
        }

        if (keep.size < minPoints) return null

        val descriptors = ByteArray(keep.size * rowBytes)
        keep.forEachIndexed { dst, src ->
            System.arraycopy(
                detected.descriptorsData,
                src * rowBytes,
                descriptors,
                dst * rowBytes,
                rowBytes,
            )
        }

        return Fingerprint(
            keypoints = keypoints,
            points3d = points,
            descriptorsData = descriptors,
            descriptorsRows = keep.size,
            descriptorsCols = detected.cols,
            descriptorsType = detected.type,
            markCenterLocal = listOf(sx / keep.size, sy / keep.size, 0f),
            // This is NOT an ARCore display-rotation capture. The value deliberately remains
            // unknown; this fingerprint lives in the separate sphereSlamFingerprint project field
            // and is never passed through the ARCore legacy-frame loader.
            captureRotationDeg = -1,
        )
    }

    private fun detectSuperPoint(slam: SlamManager, bitmap: Bitmap): DetectedFeatures? {
        val raw = slam.detectSuperPoint(bitmap) ?: return null
        if (raw.size < 2) return null
        val n = raw[0].toInt()
        val dim = raw[1].toInt()
        val posEnd = 2 + n * 2
        val descEnd = posEnd + n * dim
        if (n <= 0 || dim <= 0 || raw.size < descEnd) return null

        val positions = raw.copyOfRange(2, posEnd)
        val descFloats = raw.copyOfRange(posEnd, descEnd)
        val bytes = ByteBuffer
            .allocate(descFloats.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .apply { asFloatBuffer().put(descFloats) }
            .array()

        return DetectedFeatures(
            positions = positions,
            descriptorsData = bytes,
            rows = n,
            cols = dim,
            type = CvType.CV_32F,
        )
    }

    private fun detectOrb(bitmap: Bitmap): DetectedFeatures? {
        val rgba = Mat()
        val gray = Mat()
        val normalized = Mat()
        val mask = Mat()
        val keypoints = MatOfKeyPoint()
        val descriptors = Mat()
        val orb = ORB.create(ORB_FEATURES)
        val clahe = Imgproc.createCLAHE(2.0, org.opencv.core.Size(8.0, 8.0))
        return try {
            Utils.bitmapToMat(bitmap, rgba)
            when (rgba.channels()) {
                4 -> Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
                3 -> Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGB2GRAY)
                1 -> rgba.copyTo(gray)
                else -> return null
            }
            clahe.apply(gray, normalized)
            orb.detectAndCompute(normalized, mask, keypoints, descriptors)
            if (descriptors.empty()) return null

            val kps = keypoints.toArray()
            if (kps.size != descriptors.rows()) return null
            val bytes = ByteArray(
                descriptors.rows() * descriptors.cols() * descriptors.elemSize().toInt(),
            )
            descriptors.get(0, 0, bytes)
            val positions = FloatArray(kps.size * 2)
            kps.forEachIndexed { i, kp ->
                positions[i * 2] = kp.pt.x.toFloat()
                positions[i * 2 + 1] = kp.pt.y.toFloat()
            }
            DetectedFeatures(
                positions = positions,
                descriptorsData = bytes,
                rows = descriptors.rows(),
                cols = descriptors.cols(),
                type = descriptors.type(),
            )
        } finally {
            rgba.release()
            gray.release()
            normalized.release()
            mask.release()
            keypoints.release()
            descriptors.release()
            orb.clear()
            clahe.clear()
        }
    }
}
