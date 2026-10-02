package com.hereliesaz.graffitixr.feature.ar

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.util.PerspectiveProcessor
import com.hereliesaz.graffitixr.feature.ar.anchor.MetricMarks
import com.hereliesaz.graffitixr.feature.ar.anchor.PlaneMarks
import com.hereliesaz.sphereslam.SphereSlamPoseMath
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Builds a physically metric, fronto-parallel KPM page from the same ARCore wall plane used by the
 * target capture.
 *
 * A raw camera photograph is NOT a metric planar reference when the phone is oblique: its pixels are
 * perspective-warped relative to metres on the wall. Assigning that photograph a guessed DPI makes
 * KPM translation numerically metre-shaped but geometrically wrong. This helper first intersects
 * camera rays with ARCore's metric wall plane, chooses a conservative physical rectangle around the
 * optical centre, then rectifies that quadrilateral to an image whose pixel aspect is the physical
 * wall aspect. Only that rectified page is admitted to hybrid pose fusion.
 */
internal object HybridMetricKpmReference {
    private const val EDGE_MARGIN = 0.10f
    private const val RECT_SAFETY = 0.78f
    private const val MAX_REFERENCE_DIM = 1024
    private const val MIN_REFERENCE_DIM = 160
    private const val MIN_SIDE_M = 0.08f

    data class Geometry(
        val sourceCorners: List<Offset>, // TL, TR, BR, BL in raw camera-image pixels
        val widthMeters: Float,
        val heightMeters: Float,
        val outputWidth: Int,
        val outputHeight: Int,
        val referenceDpi: Float,
        val pageGeometry: SphereSlamPoseMath.PageGeometry,
        /** Column-major OpenGL camera-from-centered-page at capture. */
        val cameraFromPageGl: FloatArray,
    )

    data class Reference(
        val luma: ByteArray,
        val width: Int,
        val height: Int,
        val widthMeters: Float,
        val heightMeters: Float,
        val referenceDpi: Float,
        val pageGeometry: SphereSlamPoseMath.PageGeometry,
    )

    fun deriveGeometry(
        imageWidth: Int,
        imageHeight: Int,
        fx: Float,
        fy: Float,
        cx: Float,
        cy: Float,
        glView: FloatArray,
        wallPlane: FloatArray,
    ): Geometry? {
        if (
            imageWidth < 32 || imageHeight < 32 ||
            !fx.isFinite() || !fy.isFinite() || fx <= 0f || fy <= 0f ||
            !cx.isFinite() || !cy.isFinite() ||
            glView.size != 16 || glView.any { !it.isFinite() } ||
            wallPlane.size < 6 || wallPlane.any { !it.isFinite() }
        ) return null

        val centerU = cx.coerceIn(1f, imageWidth - 2f)
        val centerV = cy.coerceIn(1f, imageHeight - 2f)
        val leftU = imageWidth * EDGE_MARGIN
        val rightU = imageWidth * (1f - EDGE_MARGIN)
        val topV = imageHeight * EDGE_MARGIN
        val bottomV = imageHeight * (1f - EDGE_MARGIN)
        val samples = listOf(
            PlaneMarks.Pixel(centerU, centerV),
            PlaneMarks.Pixel(leftU, centerV),
            PlaneMarks.Pixel(rightU, centerV),
            PlaneMarks.Pixel(centerU, topV),
            PlaneMarks.Pixel(centerU, bottomV),
        )
        val cvView = MetricMarks.glViewToCv(glView)
        val projected = PlaneMarks.backProject(
            pixels = samples,
            cvView = cvView,
            planePointWorld = wallPlane.copyOfRange(0, 3),
            planeNormalWorld = wallPlane.copyOfRange(3, 6),
            fx = fx,
            fy = fy,
            cx = cx,
            cy = cy,
        )
        if (
            projected.count != samples.size ||
            !projected.kept.indices.all { projected.kept[it] == it }
        ) return null

        fun point(i: Int) = floatArrayOf(
            projected.pointsCam[i * 3],
            projected.pointsCam[i * 3 + 1],
            projected.pointsCam[i * 3 + 2],
        )
        val c = point(0)
        val l = point(1)
        val r = point(2)
        val t = point(3)
        val b = point(4)

        val ex = normalize(sub(r, l)) ?: return null
        // Image top should be +page-Y. Gram-Schmidt keeps the second axis in the wall plane while
        // removing any skew induced by the camera's oblique perspective.
        val upApprox = sub(t, b)
        val ey = normalize(sub(upApprox, scale(ex, dot(upApprox, ex)))) ?: return null

        var halfW = min(dot(sub(r, c), ex), -dot(sub(l, c), ex)) * RECT_SAFETY
        var halfH = min(dot(sub(t, c), ey), -dot(sub(b, c), ey)) * RECT_SAFETY
        if (!halfW.isFinite() || !halfH.isFinite() || halfW * 2f < MIN_SIDE_M || halfH * 2f < MIN_SIDE_M) {
            return null
        }

        var corners: List<Offset>? = null
        // Mid-edge measurements guarantee the rectangle on each principal axis, not its oblique
        // corners. Shrink conservatively until all four projected corners are actually in-frame.
        repeat(10) {
            val physicalCorners = listOf(
                add(add(c, scale(ex, -halfW)), scale(ey, halfH)),   // TL
                add(add(c, scale(ex, halfW)), scale(ey, halfH)),    // TR
                add(add(c, scale(ex, halfW)), scale(ey, -halfH)),   // BR
                add(add(c, scale(ex, -halfW)), scale(ey, -halfH)),  // BL
            )
            val candidate = physicalCorners.map { project(it, fx, fy, cx, cy) }
            if (
                candidate.all { it != null } &&
                candidate.filterNotNull().all {
                    it.x >= 1f && it.y >= 1f &&
                        it.x <= imageWidth - 2f && it.y <= imageHeight - 2f
                }
            ) {
                corners = candidate.filterNotNull()
                return@repeat
            }
            halfW *= 0.88f
            halfH *= 0.88f
        }
        val src = corners ?: return null
        val widthMeters = halfW * 2f
        val heightMeters = halfH * 2f
        if (widthMeters < MIN_SIDE_M || heightMeters < MIN_SIDE_M) return null

        // KPM's centered page axes are +X right / +Y image-up. In the CV camera frame ex and ey
        // already describe those axes; ez = ex × ey points from the wall toward the camera. Convert
        // camera-row convention exactly once into the OpenGL camera frame used everywhere else.
        val ez = normalize(cross(ex, ey)) ?: return null
        val cameraFromPageCv = floatArrayOf(
            ex[0], ex[1], ex[2], 0f,
            ey[0], ey[1], ey[2], 0f,
            ez[0], ez[1], ez[2], 0f,
            c[0], c[1], c[2], 1f,
        )
        val cameraFromPageGl = MetricMarks.glViewToCv(cameraFromPageCv)

        val sourceWidthPx = max(
            pixelDistance(src[0], src[1]),
            pixelDistance(src[3], src[2]),
        ).roundToInt().coerceAtLeast(MIN_REFERENCE_DIM)
        var outW = min(MAX_REFERENCE_DIM, sourceWidthPx)
        var outH = (outW * heightMeters / widthMeters).roundToInt().coerceAtLeast(2)
        if (outH > MAX_REFERENCE_DIM) {
            val scale = MAX_REFERENCE_DIM.toFloat() / outH.toFloat()
            outW = (outW * scale).roundToInt().coerceAtLeast(2)
            outH = MAX_REFERENCE_DIM
        }
        if (outW < MIN_REFERENCE_DIM || outH < MIN_REFERENCE_DIM) return null

        val dpi = SphereSlamPoseMath.dpiForReferenceWidth(outW, widthMeters)
        val page = SphereSlamPoseMath.pageGeometry(outW, outH, dpi)
        return Geometry(
            sourceCorners = src,
            widthMeters = widthMeters,
            heightMeters = heightMeters,
            outputWidth = outW,
            outputHeight = outH,
            referenceDpi = dpi,
            pageGeometry = page,
            cameraFromPageGl = cameraFromPageGl,
        )
    }

    fun rectify(bitmap: Bitmap, geometry: Geometry): Reference? {
        val rectified = PerspectiveProcessor.unwarpImage(
            bitmap = bitmap,
            points = geometry.sourceCorners,
            outputWidth = geometry.outputWidth,
            outputHeight = geometry.outputHeight,
        ) ?: return null
        val pixels = IntArray(rectified.width * rectified.height)
        rectified.getPixels(pixels, 0, rectified.width, 0, 0, rectified.width, rectified.height)
        val luma = ByteArray(pixels.size) { i ->
            val p = pixels[i]
            val red = (p shr 16) and 0xff
            val green = (p shr 8) and 0xff
            val blue = p and 0xff
            ((77 * red + 150 * green + 29 * blue + 128) shr 8).toByte()
        }
        return Reference(
            luma = luma,
            width = rectified.width,
            height = rectified.height,
            widthMeters = geometry.widthMeters,
            heightMeters = geometry.heightMeters,
            referenceDpi = geometry.referenceDpi,
            pageGeometry = geometry.pageGeometry,
        )
    }

    private fun project(
        p: FloatArray,
        fx: Float,
        fy: Float,
        cx: Float,
        cy: Float,
    ): Offset? {
        val z = p[2]
        if (!z.isFinite() || z <= 1e-4f) return null
        val u = fx * p[0] / z + cx
        val v = fy * p[1] / z + cy
        return if (u.isFinite() && v.isFinite()) Offset(u, v) else null
    }

    private fun pixelDistance(a: Offset, b: Offset): Float = hypot(a.x - b.x, a.y - b.y)
    private fun dot(a: FloatArray, b: FloatArray): Float = a[0]*b[0] + a[1]*b[1] + a[2]*b[2]
    private fun sub(a: FloatArray, b: FloatArray) = floatArrayOf(a[0]-b[0], a[1]-b[1], a[2]-b[2])
    private fun add(a: FloatArray, b: FloatArray) = floatArrayOf(a[0]+b[0], a[1]+b[1], a[2]+b[2])
    private fun scale(a: FloatArray, s: Float) = floatArrayOf(a[0]*s, a[1]*s, a[2]*s)
    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )
    private fun normalize(a: FloatArray): FloatArray? {
        val n = kotlin.math.sqrt(dot(a, a))
        if (!n.isFinite() || n <= 1e-5f) return null
        return scale(a, 1f / n)
    }
}
