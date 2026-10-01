package com.hereliesaz.graffitixr.feature.ar

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.sensor.CameraIntrinsics
import com.hereliesaz.graffitixr.common.util.PerspectiveProcessor
import kotlin.math.hypot
import kotlin.math.roundToInt

internal data class StandaloneAtlasPageWindow(
    val pageNo: Int,
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
)

internal data class StandaloneAtlasGrowthGeometry(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    /** Live display-frame pixels in TL, TR, BR, BL order. */
    val sourceCorners: List<Offset>,
)

internal data class StandaloneAtlasGrowthCandidate(
    val pageNo: Int,
    val bitmap: Bitmap,
    val luma: ByteArray,
    val referenceWidthUnits: Float,
    val physicallyMetric: Boolean,
    val canonicalFromPage: FloatArray,
)

/**
 * Chooses and rectifies a new KPM page while keeping page 0's centered wall frame immutable.
 *
 * The current accepted KPM pose already expresses camera-from-canonical. We intersect its optical
 * axis with canonical z=0, place an axis-aligned wall patch there, project that rectangle back into
 * the SAME display-oriented camera frame, then unwarp those pixels. The resulting page is therefore
 * Euclidean wall geometry, not a perspective camera image pretending to be a flat metric page.
 */
internal object StandaloneAtlasGrowth {
    const val MAX_PAGES = 12
    const val MIN_GROW_INLIERS = 18
    const val MAX_GROW_REPROJECTION_ERROR = 4.0f
    const val MIN_GROW_INTERVAL_MS = 3_000L

    private const val MIN_BASELINE_RATIO = 0.45f
    private const val MIN_OVERLAP_RATIO = 0.15f
    private const val FRAME_MARGIN_RATIO = 0.06f
    private const val MIN_SCALE = 0.45f
    private const val SCALE_STEP = 0.85f
    private const val MAX_REFERENCE_WIDTH_PX = 1024

    fun propose(
        cameraFromCanonicalOpenGl: FloatArray,
        intrinsics: CameraIntrinsics,
        frameWidth: Int,
        frameHeight: Int,
        rootWidthUnits: Float,
        rootHeightUnits: Float,
        existingPages: List<StandaloneAtlasPageWindow>,
    ): StandaloneAtlasGrowthGeometry? {
        if (
            cameraFromCanonicalOpenGl.size != 16 ||
            frameWidth <= 0 ||
            frameHeight <= 0 ||
            rootWidthUnits <= 0f ||
            rootHeightUnits <= 0f ||
            existingPages.isEmpty()
        ) return null

        val center = backProjectToWall(
            intrinsics.cx,
            intrinsics.cy,
            cameraFromCanonicalOpenGl,
            intrinsics,
        ) ?: return null

        val nearest = existingPages.minOfOrNull {
            hypot(center.first - it.centerX, center.second - it.centerY)
        } ?: return null
        if (nearest < rootWidthUnits * MIN_BASELINE_RATIO) return null

        var scale = 1f
        while (scale >= MIN_SCALE) {
            val width = rootWidthUnits * scale
            val height = rootHeightUnits * scale
            val cornersCanonical = listOf(
                center.first - width * 0.5f to center.second + height * 0.5f,
                center.first + width * 0.5f to center.second + height * 0.5f,
                center.first + width * 0.5f to center.second - height * 0.5f,
                center.first - width * 0.5f to center.second - height * 0.5f,
            )
            val projected = cornersCanonical.map { (x, y) ->
                projectWallPoint(
                    x,
                    y,
                    cameraFromCanonicalOpenGl,
                    intrinsics,
                )
            }
            if (projected.all { it != null }) {
                val corners = projected.filterNotNull()
                val marginX = frameWidth * FRAME_MARGIN_RATIO
                val marginY = frameHeight * FRAME_MARGIN_RATIO
                val inside = corners.all {
                    it.x >= marginX &&
                        it.x <= frameWidth - marginX &&
                        it.y >= marginY &&
                        it.y <= frameHeight - marginY
                }
                if (
                    inside &&
                    existingPages.any {
                        overlapRatio(
                            center.first,
                            center.second,
                            width,
                            height,
                            it,
                        ) >= MIN_OVERLAP_RATIO
                    }
                ) {
                    return StandaloneAtlasGrowthGeometry(
                        centerX = center.first,
                        centerY = center.second,
                        width = width,
                        height = height,
                        sourceCorners = corners,
                    )
                }
            }
            scale *= SCALE_STEP
        }
        return null
    }

    fun rectify(
        frameLuma: ByteArray,
        frameWidth: Int,
        frameHeight: Int,
        geometry: StandaloneAtlasGrowthGeometry,
    ): Pair<Bitmap, ByteArray>? {
        if (frameLuma.size != frameWidth * frameHeight) return null
        val source = lumaBitmap(frameLuma, frameWidth, frameHeight)
        val unwarped = PerspectiveProcessor.unwarpImage(source, geometry.sourceCorners) ?: return null
        if (unwarped.width <= 0 || unwarped.height <= 0) return null

        val targetWidth = minOf(unwarped.width, MAX_REFERENCE_WIDTH_PX).coerceAtLeast(64)
        val targetHeight = (
            targetWidth.toFloat() * geometry.height / geometry.width
        ).roundToInt().coerceAtLeast(64)
        val rectified = if (unwarped.width == targetWidth && unwarped.height == targetHeight) {
            unwarped
        } else {
            Bitmap.createScaledBitmap(unwarped, targetWidth, targetHeight, true)
        }
        return rectified to bitmapToLuma(rectified)
    }

    fun canonicalFromPage(centerX: Float, centerY: Float): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        centerX, centerY, 0f, 1f,
    )

    private fun overlapRatio(
        cx: Float,
        cy: Float,
        width: Float,
        height: Float,
        other: StandaloneAtlasPageWindow,
    ): Float {
        val left = maxOf(cx - width * 0.5f, other.centerX - other.width * 0.5f)
        val right = minOf(cx + width * 0.5f, other.centerX + other.width * 0.5f)
        val bottom = maxOf(cy - height * 0.5f, other.centerY - other.height * 0.5f)
        val top = minOf(cy + height * 0.5f, other.centerY + other.height * 0.5f)
        val intersection = maxOf(0f, right - left) * maxOf(0f, top - bottom)
        val denom = minOf(width * height, other.width * other.height)
        return if (denom > 0f) intersection / denom else 0f
    }

    private fun projectWallPoint(
        x: Float,
        y: Float,
        view: FloatArray,
        intrinsics: CameraIntrinsics,
    ): Offset? {
        // OpenGL eye: +X right, +Y up, -Z forward. Convert only the camera-coordinate signs needed
        // by the Android/OpenCV pinhole convention (+X right, +Y down, +Z forward).
        val gx = view[0] * x + view[4] * y + view[12]
        val gy = view[1] * x + view[5] * y + view[13]
        val gz = view[2] * x + view[6] * y + view[14]
        val z = -gz
        if (!z.isFinite() || z <= 1e-4f) return null
        val u = intrinsics.fx * gx / z + intrinsics.cx
        val v = intrinsics.fy * (-gy) / z + intrinsics.cy
        return if (u.isFinite() && v.isFinite()) Offset(u, v) else null
    }

    private fun backProjectToWall(
        u: Float,
        v: Float,
        view: FloatArray,
        intrinsics: CameraIntrinsics,
    ): Pair<Float, Float>? {
        // camera_from_canonical in OpenCV coordinates, reconstructed from the OpenGL view.
        val r00 = view[0]; val r01 = view[4]; val r02 = view[8]
        val r10 = -view[1]; val r11 = -view[5]; val r12 = -view[9]
        val r20 = -view[2]; val r21 = -view[6]; val r22 = -view[10]
        val tx = view[12]; val ty = -view[13]; val tz = -view[14]

        // Canonical camera centre C = -R^T t.
        val cx = -(r00 * tx + r10 * ty + r20 * tz)
        val cy = -(r01 * tx + r11 * ty + r21 * tz)
        val cz = -(r02 * tx + r12 * ty + r22 * tz)

        val dxCam = (u - intrinsics.cx) / intrinsics.fx
        val dyCam = (v - intrinsics.cy) / intrinsics.fy
        val dzCam = 1f
        val dx = r00 * dxCam + r10 * dyCam + r20 * dzCam
        val dy = r01 * dxCam + r11 * dyCam + r21 * dzCam
        val dz = r02 * dxCam + r12 * dyCam + r22 * dzCam
        if (kotlin.math.abs(dz) < 1e-6f) return null
        val lambda = -cz / dz
        if (!lambda.isFinite() || lambda <= 0f) return null
        val x = cx + lambda * dx
        val y = cy + lambda * dy
        return if (x.isFinite() && y.isFinite()) x to y else null
    }

    private fun lumaBitmap(bytes: ByteArray, width: Int, height: Int): Bitmap {
        val pixels = IntArray(bytes.size)
        for (i in bytes.indices) {
            val y = bytes[i].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (y shl 16) or (y shl 8) or y
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun bitmapToLuma(bitmap: Bitmap): ByteArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return ByteArray(pixels.size) { i ->
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            ((77 * r + 150 * g + 29 * b) shr 8).toByte()
        }
    }
}
