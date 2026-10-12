package com.hereliesaz.graffitixr.feature.ar.anticipation

import com.hereliesaz.graffitixr.common.model.AnticipationSettings
import com.hereliesaz.sphereslam.camera.CameraIntrinsics
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The half-sphere of anticipated views of a target ([AnticipationSettings]): the target at the core,
 * the capture view at the pole, meridians (latitude) from the pole to the equator cut by circles
 * (longitude); a view at the pole and at every cut, aimed at the core, none on the equator; nested
 * half-spheres (distance) from the capture distance in toward the target (zooming in); each view
 * taken at every roll; a nearer half-sphere repeated over a grid of aim points across the target
 * (`AnticipationSettings.aimsPerSide`), so a close-up of any part of the wall is anticipated, not
 * only of its middle. Each is what the matching camera ([CameraIntrinsics]) would see from there,
 * cropped to its frame: near ones large and strongly foreshortened. SphereSLAM learns them as
 * further images of the page (`AnticipatedView`), features mapped back through [Preview.pageFromView].
 *
 * Pixels use the pixel-centre convention (pixel `i` at coordinate `i`) on both sides, as OpenCV's
 * warps and KPM's feature coordinates do.
 */
internal object HalfSphere {

    /** What a preview shows: the square-on view (a pole) or a view from a cut. */
    enum class Kind { POLE, CUT }

    /**
     * One preview: [luma] ([width] × [height], tightly packed) and [pageFromView], the row-major 3×3
     * mapping a preview pixel back to the reference pixel it shows. [cameraFromPage] and [scale] are
     * the view it stands for (see [Plan]).
     */
    class Preview(
        val kind: Kind,
        val label: String,
        val luma: ByteArray,
        val width: Int,
        val height: Int,
        val pageFromView: FloatArray,
        val cameraFromPage: DoubleArray,
        val scale: Double,
    )

    /**
     * Where a plan sits on the half-spheres of an [AnticipationSettings]: half-sphere [shell] (0
     * nearest), circle [circle] (0 the pole, 1 nearest it), meridian [meridian] (0 to the page's right,
     * counterclockwise), roll [roll] (0 upright, then evenly round), and aim point [aim] (row-major
     * from the target's top left; 0 the centre when the half-sphere has one aim).
     */
    data class Site(val shell: Int, val circle: Int, val meridian: Int, val roll: Int = 0, val aim: Int = 0)

    /**
     * A view as the build order compares them: [cameraFromPage] and [scale] (see [Plan]), the aim
     * point ([aimX], [aimY], target widths from the target's centre, y up) and [footprint], the
     * frame's width there in target widths.
     */
    class Pose(val cameraFromPage: DoubleArray, val scale: Double, val aimX: Double, val aimY: Double, val footprint: Double) {
        companion object {
            /** The reference itself: square-on, whole. */
            val REFERENCE = Pose(FRONTAL, 1.0, 0.0, 0.0, 1.0)
        }
    }

    /**
     * A preview to render: the reference-to-preview homography and the preview's size, and the view it
     * stands for: [cameraFromPage], the row-major rotation from the page's millimetre frame (x right,
     * y up, z out of the wall) to a camera's (x right, y down, z forward) that would see the page as
     * the preview shows it, and [scale], the preview's pixels per reference pixel at the target's
     * centre. [label] names the view (the same view always gets the same label); [site] places it.
     */
    class Plan(
        val kind: Kind,
        val label: String,
        val viewFromPage: DoubleArray,
        val width: Int,
        val height: Int,
        val cameraFromPage: DoubleArray = FRONTAL,
        val scale: Double = 1.0,
        val site: Site = Site(0, 0, 0),
        val aimX: Double = 0.0,
        val aimY: Double = 0.0,
        val footprint: Double = 1.0,
    ) {
        val pose: Pose get() = Pose(cameraFromPage, scale, aimX, aimY, footprint)
    }

    /** The page seen square-on, upright: page y up is image y down, the wall faces the camera. */
    val FRONTAL = doubleArrayOf(1.0, 0.0, 0.0, 0.0, -1.0, 0.0, 0.0, 0.0, -1.0)

    /** Smaller previews than this (short side, px) are not rendered: KPM finds too little in them. */
    const val MIN_SIDE_PX = 64

    /**
     * The distance (target widths) the reference was captured from: where the target fills
     * [camera]'s frame as it filled the reference ([width] × [height]; its width along the frame's
     * width when both are the same way round, else along its height).
     */
    fun captureDistance(width: Int, height: Int, camera: CameraIntrinsics): Double =
        if ((width >= height) == (camera.width >= camera.height)) camera.fx.toDouble() / camera.width
        else camera.fy.toDouble() / camera.height

    /**
     * The half-spheres' radii in target widths: [count] evenly spaced from the [capture] distance in
     * toward the target (`capture × i / count`, i = 1 … count): 1 is the capture distance, 3 are ⅓, ⅔
     * and 1 of it. The views zoom in from the reference, never out (KPM's scale range covers a target
     * smaller than it was captured).
     */
    fun shells(count: Int, capture: Double): List<Double> =
        if (count <= 0) emptyList() else List(count) { i -> capture * (i + 1) / count }

    /**
     * The previews of a [width] × [height] reference (the whole target) at [resolution], as [camera]
     * (the matched image's intrinsics) would see them: on each half-sphere, at each of its aim points
     * (the centres of an `aimsPerSide × aimsPerSide` grid over the target), the pole and every cut of
     * a meridian by a circle (the equator, edge-on, excluded), each at every roll. Partial views (the
     * target overflowing the frame, part of it behind the camera) are kept: up close they are all
     * there is. Views that leave too little of the target in frame (under [MIN_SIDE_PX]) are dropped.
     */
    fun plans(width: Int, height: Int, camera: CameraIntrinsics, resolution: AnticipationSettings): List<Plan> {
        val out = ArrayList<Plan>()
        val radii = shells(resolution.distance, captureDistance(width, height, camera))
        val aspect = height.toDouble() / width
        for ((si, r) in radii.withIndex()) {
            val n = resolution.aimsPerSide(si)
            for (aim in 0 until n * n) {
                val ax = ((aim % n) + 0.5) / n - 0.5
                val ay = (0.5 - ((aim / n) + 0.5) / n) * aspect
                for (k in 0 until resolution.roll) {
                    val roll = k * 360.0 / resolution.roll
                    fun at(tilt: Double, azimuth: Double, kind: Kind, site: Site) =
                        view(width, height, camera, r, tilt, azimuth, roll, kind, site, ax, ay, if (n > 1) "aim ${aim % n + 1},${aim / n + 1}/$n" else null)
                    at(0.0, 0.0, Kind.POLE, Site(si, 0, 0, k, aim))?.let { out += it }
                    for (c in 1..resolution.longitude) {
                        val tilt = c * 90.0 / (resolution.longitude + 1)
                        for (m in 0 until resolution.latitude) {
                            at(tilt, m * 360.0 / resolution.latitude, Kind.CUT, Site(si, c, m, k, aim))?.let { out += it }
                        }
                    }
                }
            }
        }
        return out
    }

    /**
     * The view from [distance] target widths, [tiltDeg] from the pole toward [azimuthDeg] (0 the
     * page's right, counterclockwise), aimed at the core, upright (image up toward the page's up) and
     * then turned [rollDeg] about its optical axis; cropped to [camera]'s frame and to the target.
     * The core is the aim point ([aimX], [aimY]: target widths from the target's centre, y up),
     * named by [aimLabel] when there are several. Null when too little of the target is in view.
     */
    fun view(
        width: Int,
        height: Int,
        camera: CameraIntrinsics,
        distance: Double,
        tiltDeg: Double,
        azimuthDeg: Double,
        rollDeg: Double,
        kind: Kind,
        site: Site,
        aimX: Double = 0.0,
        aimY: Double = 0.0,
        aimLabel: String? = null,
    ): Plan? {
        val t = Math.toRadians(tiltDeg); val a = Math.toRadians(azimuthDeg)
        val dir = doubleArrayOf(sin(t) * cos(a), sin(t) * sin(a), cos(t))
        val centre = doubleArrayOf(aimX + distance * dir[0], aimY + distance * dir[1], distance * dir[2])
        val r = mul(roll(rollDeg), lookingFrom(dir))
        // Reference pixel (x, y) → page point in target widths (y up, z = 0) → camera → pixel.
        val cx = (width - 1) / 2.0; val cy = (height - 1) / 2.0
        val w = width.toDouble()
        fun cameraPoint(x: Double, y: Double): DoubleArray {
            val p = doubleArrayOf((x - cx) / w - centre[0], -(y - cy) / w - centre[1], -centre[2])
            return DoubleArray(3) { i -> r[i * 3] * p[0] + r[i * 3 + 1] * p[1] + r[i * 3 + 2] * p[2] }
        }
        // Homography columns: d/dx, d/dy and the camera point of reference pixel (0, 0).
        val o = cameraPoint(0.0, 0.0); val dx = cameraPoint(1.0, 0.0); val dy = cameraPoint(0.0, 1.0)
        val m = doubleArrayOf(
            dx[0] - o[0], dy[0] - o[0], o[0],
            dx[1] - o[1], dy[1] - o[1], o[1],
            dx[2] - o[2], dy[2] - o[2], o[2],
        )
        val k = doubleArrayOf(camera.fx.toDouble(), 0.0, camera.cx.toDouble(), 0.0, camera.fy.toDouble(), camera.cy.toDouble(), 0.0, 0.0, 1.0)
        val h = mul(k, m)
        // The target's outline in camera space, clipped to the space in front of the camera, projected.
        val corners = listOf(-0.5 to -0.5, width - 0.5 to -0.5, width - 0.5 to height - 0.5, -0.5 to height - 0.5)
            .map { (x, y) -> cameraPoint(x, y) }
        val outline = clipInFront(corners).map { p ->
            doubleArrayOf(camera.fx * p[0] / p[2] + camera.cx, camera.fy * p[1] / p[2] + camera.cy)
        }
        if (outline.size < 3) return null
        val minX = max(0.0, floor(outline.minOf { it[0] })); val maxX = min(camera.width - 1.0, ceil(outline.maxOf { it[0] }))
        val minY = max(0.0, floor(outline.minOf { it[1] })); val maxY = min(camera.height - 1.0, ceil(outline.maxOf { it[1] }))
        val pw = (maxX - minX).toInt() + 1; val ph = (maxY - minY).toInt() + 1
        if (min(pw, ph) < MIN_SIDE_PX) return null
        val shift = doubleArrayOf(1.0, 0.0, -minX, 0.0, 1.0, -minY, 0.0, 0.0, 1.0)
        val label = when (kind) {
            Kind.POLE -> "pole ${fmt(distance)}w"
            Kind.CUT -> "${fmt(distance)}w tilt ${fmt(tiltDeg)}° toward ${fmt(azimuthDeg)}°"
        } + (if (rollDeg != 0.0) " roll ${fmt(rollDeg)}°" else "") + (aimLabel?.let { " $it" } ?: "")
        val footprint = distance * camera.width / camera.fx
        return Plan(kind, label, mul(shift, h), pw, ph, r, camera.fx / (distance * w), site, aimX, aimY, footprint)
    }

    private fun fmt(v: Double) = String.format(java.util.Locale.ROOT, "%.4g", v).trimEnd('0').trimEnd('.')

    /**
     * Rotation (page frame → camera) of a camera at [dir] from the core (page frame, unit), aimed at
     * the core, its image up toward the page's up. Square-on it is [FRONTAL].
     */
    private fun lookingFrom(dir: DoubleArray): DoubleArray {
        val f = DoubleArray(3) { -dir[it] }
        // Image x ⟂ the forward axis and the page's up; image y down (completes a right-handed frame).
        val up = doubleArrayOf(0.0, 1.0, 0.0)
        var x = cross(f, up)
        val n = sqrt(x.sumOf { it * it })
        x = if (n < 1e-9) doubleArrayOf(1.0, 0.0, 0.0) else DoubleArray(3) { x[it] / n }
        val y = cross(f, x)
        return doubleArrayOf(x[0], x[1], x[2], y[0], y[1], y[2], f[0], f[1], f[2])
    }

    /** A turn of the camera by [deg] about its optical axis (the image turns the same way). */
    private fun roll(deg: Double): DoubleArray {
        val a = Math.toRadians(deg)
        val c = cos(a); val s = sin(a)
        return doubleArrayOf(c, -s, 0.0, s, c, 0.0, 0.0, 0.0, 1.0)
    }

    private fun cross(a: DoubleArray, b: DoubleArray) =
        doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

    /** [polygon] (camera space) clipped to z ≥ a small near distance (Sutherland–Hodgman, one plane). */
    private fun clipInFront(polygon: List<DoubleArray>): List<DoubleArray> {
        val near = 1e-3
        val out = ArrayList<DoubleArray>()
        for (i in polygon.indices) {
            val a = polygon[i]; val b = polygon[(i + 1) % polygon.size]
            val aIn = a[2] >= near; val bIn = b[2] >= near
            if (aIn) out += a
            if (aIn != bIn) {
                val t = (near - a[2]) / (b[2] - a[2])
                out += DoubleArray(3) { a[it] + t * (b[it] - a[it]) }
            }
        }
        return out
    }

    /**
     * [plans] reordered so every prefix covers the views evenly: starting from the reference itself
     * ([Pose.REFERENCE]), each next plan is the one farthest from all chosen so far, by [cost]
     * (`AnticipationFeeder.priorityCost`). Ties keep [plans]' order.
     */
    fun prioritized(plans: List<Plan>, cost: (Pose, Pose) -> Double): List<Plan> {
        val left = plans.toMutableList()
        val chosen = ArrayList<Plan>(plans.size)
        // Distance from each remaining plan to its nearest chosen view (the reference to start).
        val nearest = left.map { cost(it.pose, Pose.REFERENCE) }.toMutableList()
        while (left.isNotEmpty()) {
            var best = 0
            for (i in 1 until left.size) if (nearest[i] > nearest[best] + 1e-9) best = i
            val pick = left.removeAt(best)
            nearest.removeAt(best)
            chosen += pick
            val picked = pick.pose
            for (i in left.indices) nearest[i] = minOf(nearest[i], cost(left[i].pose, picked))
        }
        return chosen
    }

    /**
     * Angle in degrees between two views ([cameraFromPage] rotations): how far a camera at one must
     * turn to be at the other.
     */
    fun viewAngleDeg(a: DoubleArray, b: DoubleArray): Double {
        // trace(A·Bᵀ) = 1 + 2 cos θ.
        var trace = 0.0
        for (i in 0..2) for (k in 0..2) trace += a[i * 3 + k] * b[i * 3 + k]
        return Math.toDegrees(kotlin.math.acos(((trace - 1.0) / 2.0).coerceIn(-1.0, 1.0)))
    }

    /** Renders [plan] from the reference ([luma], [width] × [height]). */
    fun render(luma: ByteArray, width: Int, height: Int, plan: Plan, useOpenCv: Boolean = true): Preview {
        val pageFromView = invert(plan.viewFromPage) ?: error("singular preview homography")
        val pixels = (if (useOpenCv) renderOpenCv(luma, width, height, plan) else null)
            ?: renderKotlin(luma, width, height, plan, pageFromView)
        maskBehind(pixels, plan.width, plan.height, pageFromView, luma)
        return Preview(plan.kind, plan.label, pixels, plan.width, plan.height, FloatArray(9) { pageFromView[it].toFloat() }, plan.cameraFromPage, plan.scale)
    }

    /**
     * OpenCV's warp (`warpPerspective`, bilinear, border replicated so the edge adds no false
     * corners), after an area shrink (`resize`, `INTER_AREA`) when the preview is much smaller than the
     * reference so it doesn't alias. Null when OpenCV isn't loaded.
     */
    private fun renderOpenCv(luma: ByteArray, width: Int, height: Int, plan: Plan): ByteArray? = try {
        val src = Mat(height, width, CvType.CV_8UC1).apply { put(0, 0, luma) }
        var input = src
        var h = plan.viewFromPage
        val shrink = shrinkFactor(plan.viewFromPage, width, height)
        if (shrink < 0.75) {
            val small = Mat()
            val sw = max(1, Math.round(width * shrink).toInt()); val sh = max(1, Math.round(height * shrink).toInt())
            Imgproc.resize(src, small, Size(sw.toDouble(), sh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            val kx = sw.toDouble() / width; val ky = sh.toDouble() / height
            // Small-image pixel → reference pixel, folded into the warp.
            val up = doubleArrayOf(1 / kx, 0.0, 0.5 / kx - 0.5, 0.0, 1 / ky, 0.5 / ky - 0.5, 0.0, 0.0, 1.0)
            h = mul(h, up)
            input = small
        }
        val hm = Mat(3, 3, CvType.CV_64F).apply { put(0, 0, *h) }
        val dst = Mat()
        Imgproc.warpPerspective(input, dst, hm, Size(plan.width.toDouble(), plan.height.toDouble()), Imgproc.INTER_LINEAR, org.opencv.core.Core.BORDER_REPLICATE)
        ByteArray(plan.width * plan.height).also { dst.get(0, 0, it) }
            .also { src.release(); if (input !== src) input.release(); hm.release(); dst.release() }
    } catch (_: LinkageError) {
        null
    }

    /** The same warp without OpenCV: bilinear, with a box pre-shrink, edge pixels replicated. */
    private fun renderKotlin(luma: ByteArray, width: Int, height: Int, plan: Plan, pageFromView: DoubleArray): ByteArray {
        var src = luma; var sw = width; var sh = height
        var back = pageFromView
        val shrink = shrinkFactor(plan.viewFromPage, width, height)
        if (shrink < 0.75) {
            sw = max(1, Math.round(width * shrink).toInt()); sh = max(1, Math.round(height * shrink).toInt())
            src = boxShrink(luma, width, height, sw, sh)
            val kx = sw.toDouble() / width; val ky = sh.toDouble() / height
            val down = doubleArrayOf(kx, 0.0, 0.5 * kx - 0.5, 0.0, ky, 0.5 * ky - 0.5, 0.0, 0.0, 1.0)
            back = mul(down, pageFromView)
        }
        val out = ByteArray(plan.width * plan.height)
        for (v in 0 until plan.height) for (u in 0 until plan.width) {
            val w = back[6] * u + back[7] * v + back[8]
            val x = ((back[0] * u + back[1] * v + back[2]) / w).coerceIn(0.0, sw - 1.0)
            val y = ((back[3] * u + back[4] * v + back[5]) / w).coerceIn(0.0, sh - 1.0)
            val x0 = min(floor(x).toInt(), sw - 2).coerceAtLeast(0); val y0 = min(floor(y).toInt(), sh - 2).coerceAtLeast(0)
            val fx = x - x0; val fy = y - y0
            val i = y0 * sw + x0
            fun p(k: Int) = (src[k].toInt() and 0xff).toDouble()
            val x1 = if (sw > 1) 1 else 0; val y1 = if (sh > 1) sw else 0
            val value = (p(i) * (1 - fx) + p(i + x1) * fx) * (1 - fy) + (p(i + y1) * (1 - fx) + p(i + y1 + x1) * fx) * fy
            out[v * plan.width + u] = (value + 0.5).toInt().coerceIn(0, 255).toByte()
        }
        return out
    }

    /**
     * Fills the preview's pixels whose rays miss the target's plane (above its horizon in a steep,
     * near view) with the reference's mean level: the warp would mirror the plane into them. The
     * test is linear in the pixel, so a preview whose corners all see the plane has none.
     */
    private fun maskBehind(pixels: ByteArray, w: Int, h: Int, pageFromView: DoubleArray, reference: ByteArray) {
        fun sees(u: Int, v: Int) = pageFromView[6] * u + pageFromView[7] * v + pageFromView[8] > 0
        if (sees(0, 0) && sees(w - 1, 0) && sees(0, h - 1) && sees(w - 1, h - 1)) return
        var sum = 0L
        for (b in reference) sum += b.toInt() and 0xff
        val mean = (sum / reference.size.coerceAtLeast(1)).toInt().toByte()
        for (v in 0 until h) for (u in 0 until w) if (!sees(u, v)) pixels[v * w + u] = mean
    }

    /** [luma] ([width] × [height]) area-averaged down to [w] × [h]. */
    private fun boxShrink(luma: ByteArray, width: Int, height: Int, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            val y0 = y * height / h; val y1 = maxOf(y0 + 1, (y + 1) * height / h)
            for (x in 0 until w) {
                val x0 = x * width / w; val x1 = maxOf(x0 + 1, (x + 1) * width / w)
                var sum = 0; var n = 0
                for (yy in y0 until y1) for (xx in x0 until x1) { sum += luma[yy * width + xx].toInt() and 0xff; n++ }
                out[y * w + x] = (sum / n).toByte()
            }
        }
        return out
    }

    /** How much the preview shrinks the reference at its most compressed corner (≤ 1 shrinks). */
    private fun shrinkFactor(h: DoubleArray, width: Int, height: Int): Double {
        var least = Double.MAX_VALUE
        for ((x, y) in listOf(0.0 to 0.0, width - 1.0 to 0.0, width - 1.0 to height - 1.0, 0.0 to height - 1.0, (width - 1) / 2.0 to (height - 1) / 2.0)) {
            val p = apply(h, x, y) ?: continue
            val px = apply(h, x + 1, y) ?: continue
            val py = apply(h, x, y + 1) ?: continue
            val dx = sqrt((px[0] - p[0]) * (px[0] - p[0]) + (px[1] - p[1]) * (px[1] - p[1]))
            val dy = sqrt((py[0] - p[0]) * (py[0] - p[0]) + (py[1] - p[1]) * (py[1] - p[1]))
            least = min(least, max(dx, dy))
        }
        return if (least == Double.MAX_VALUE) 1.0 else least.coerceAtMost(1.0)
    }

    fun apply(h: DoubleArray, x: Double, y: Double): DoubleArray? {
        val w = h[6] * x + h[7] * y + h[8]
        if (w <= 1e-9) return null
        return doubleArrayOf((h[0] * x + h[1] * y + h[2]) / w, (h[3] * x + h[4] * y + h[5]) / w)
    }

    fun mul(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(9) { i ->
        val r = i / 3; val c = i % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

    fun invert(m: DoubleArray): DoubleArray? {
        val det = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
        if (kotlin.math.abs(det) < 1e-18 || !det.isFinite()) return null
        val d = 1.0 / det
        return doubleArrayOf(
            (m[4] * m[8] - m[5] * m[7]) * d, (m[2] * m[7] - m[1] * m[8]) * d, (m[1] * m[5] - m[2] * m[4]) * d,
            (m[5] * m[6] - m[3] * m[8]) * d, (m[0] * m[8] - m[2] * m[6]) * d, (m[2] * m[3] - m[0] * m[5]) * d,
            (m[3] * m[7] - m[4] * m[6]) * d, (m[1] * m[6] - m[0] * m[7]) * d, (m[0] * m[4] - m[1] * m[3]) * d,
        )
    }
}
