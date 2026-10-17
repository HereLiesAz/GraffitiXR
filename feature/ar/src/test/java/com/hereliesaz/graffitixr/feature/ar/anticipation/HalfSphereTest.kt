package com.hereliesaz.graffitixr.feature.ar.anticipation

import com.hereliesaz.graffitixr.common.model.AnticipationSettings
import com.hereliesaz.sphereslam.camera.CameraIntrinsics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** [HalfSphere] geometry and rendering, on the JVM (no OpenCV: the Kotlin warp). */
class HalfSphereTest {

    private val w = 400
    private val h = 300

    /** 320 × 240, a phone's field of view: the reference was captured 260 / 320 target widths away. */
    private val camera = CameraIntrinsics(260f, 260f, 159.5f, 119.5f, 320, 240)

    /** Dark, with bright 13×13 dots on a grid: a dot survives a steep tilt and can be found again. */
    private fun reference(): ByteArray = ByteArray(w * h).also { img ->
        for (gy in 1..5) for (gx in 1..7) {
            val cx = gx * 50; val cy = gy * 50
            for (dy in -6..6) for (dx in -6..6) img[(cy + dy) * w + cx + dx] = 250.toByte()
        }
    }

    private fun plans(s: AnticipationSettings) = HalfSphere.plans(w, h, camera, s)

    private fun lookingFrom(dir: DoubleArray): DoubleArray {
        val n = sqrt(dir.sumOf { it * it })
        val f = DoubleArray(3) { -dir[it] / n }
        fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
        val x = cross(f, doubleArrayOf(0.0, 1.0, 0.0)).let { v -> val l = sqrt(v.sumOf { it * it }); DoubleArray(3) { v[it] / l } }
        val y = cross(f, x)
        return doubleArrayOf(x[0], x[1], x[2], y[0], y[1], y[2], f[0], f[1], f[2])
    }

    private fun rollBy(deg: Double): DoubleArray {
        val a = Math.toRadians(deg)
        return doubleArrayOf(cos(a), -sin(a), 0.0, sin(a), cos(a), 0.0, 0.0, 0.0, 1.0)
    }

    private fun dir(tiltDeg: Double, azimuthDeg: Double): DoubleArray {
        val t = Math.toRadians(tiltDeg); val a = Math.toRadians(azimuthDeg)
        return doubleArrayOf(sin(t) * cos(a), sin(t) * sin(a), cos(t))
    }

    private fun edge(plan: HalfSphere.Plan, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val a = HalfSphere.apply(plan.viewFromPage, ax, ay)!!
        val b = HalfSphere.apply(plan.viewFromPage, bx, by)!!
        return hypot(a[0] - b[0], a[1] - b[1])
    }

    @Test
    fun `each half-sphere has its pole and every cut, none on the equator, at every roll and aim`() {
        val s = AnticipationSettings(distance = 3, latitude = 4, longitude = 1)
        val p = plans(s)
        assertEquals(s.maxViews, p.size)
        assertEquals(9 + 4 + 1, p.count { it.kind == HalfSphere.Kind.POLE })
        assertTrue(p.filter { it.kind == HalfSphere.Kind.CUT }.all { it.label.contains("tilt 45°") })
        assertEquals(setOf(0, 1, 2, 3), p.filter { it.site.circle == 1 }.map { it.site.meridian }.toSet())
        // Three circles split the quarter into four.
        val three = plans(AnticipationSettings(1, 4, 3))
        assertEquals(1 + 4 * 3, three.size)
        assertEquals(setOf("22.5", "45", "67.5"), three.filter { it.kind == HalfSphere.Kind.CUT }.map { it.label.substringAfter("tilt ").substringBefore("°") }.toSet())
        // Roll 2: upright and upside down.
        val rolled = plans(s.copy(roll = 2))
        assertEquals(2 * p.size, rolled.size)
        for (plan in rolled.filter { it.site.roll == 1 }) {
            assertTrue(plan.label, plan.label.contains(" roll 180°"))
            val upright = rolled.single { it.site == plan.site.copy(roll = 0) }
            assertEquals(plan.label, 180.0, HalfSphere.viewAngleDeg(upright.cameraFromPage, plan.cameraFromPage), 1e-6)
        }
        assertTrue(plans(AnticipationSettings(distance = 0)).isEmpty())
        assertEquals(p.size, p.map { it.label }.distinct().size)
    }

    @Test
    fun `half-spheres zoom in from the capture distance`() {
        val capture = HalfSphere.captureDistance(w, h, camera)
        assertEquals(260.0 / 320.0, capture, 1e-9)
        assertEquals(listOf(capture / 3, 2 * capture / 3, capture), HalfSphere.shells(3, capture))
        // A portrait camera on a landscape reference: the target's width lies along the frame's height.
        val portrait = CameraIntrinsics(260f, 250f, 119.5f, 159.5f, 240, 320)
        assertEquals(250.0 / 320.0, HalfSphere.captureDistance(w, h, portrait), 1e-9)
        // The outermost pole is the reference as captured: the target fills the frame's width.
        val pole = plans(AnticipationSettings(1, 0, 0)).single()
        assertEquals(1.0 * camera.width / w, pole.scale, 1e-9)
    }

    @Test
    fun `each preview is what the camera sees from its place on the half-sphere, cropped`() {
        val s = AnticipationSettings(3, 8, 3, roll = 3)
        val radii = HalfSphere.shells(3, HalfSphere.captureDistance(w, h, camera))
        for (plan in plans(s)) {
            val site = plan.site
            val d = dir(site.circle * 90.0 / (s.longitude + 1), site.meridian * 360.0 / s.latitude)
            val r = HalfSphere.mul(rollBy(site.roll * 360.0 / s.roll), lookingFrom(d))
            assertEquals(plan.label, 0.0, HalfSphere.viewAngleDeg(r, plan.cameraFromPage), 1e-4)
            // The core is the aim: the centre of its cell of an n × n grid over the target.
            val n = s.aimsPerSide(site.shell)
            val aim = doubleArrayOf(((site.aim % n) + 0.5) / n - 0.5, (0.5 - ((site.aim / n) + 0.5) / n) * h / w)
            assertEquals(plan.label, aim[0], plan.aimX, 1e-12)
            assertEquals(plan.label, aim[1], plan.aimY, 1e-12)
            val c = DoubleArray(3) { if (it < 2) aim[it] + radii[site.shell] * d[it] else radii[site.shell] * d[it] }
            fun project(x: Double, y: Double): DoubleArray {
                val p = doubleArrayOf((x - (w - 1) / 2.0) / w - c[0], -(y - (h - 1) / 2.0) / w - c[1], -c[2])
                val q = DoubleArray(3) { i -> r[i * 3] * p[0] + r[i * 3 + 1] * p[1] + r[i * 3 + 2] * p[2] }
                return doubleArrayOf(camera.fx * q[0] / q[2] + camera.cx, camera.fy * q[1] / q[2] + camera.cy)
            }
            // The aim's reference pixel, always in front of the camera (it is looked at).
            val axPx = aim[0] * w + (w - 1) / 2.0; val ayPx = (h - 1) / 2.0 - aim[1] * w
            val o = project(axPx, ayPx)
            val v0 = HalfSphere.apply(plan.viewFromPage, axPx, ayPx)!!
            for ((x, y) in listOf(10.0 to 10.0, 390.0 to 20.0, 200.0 to 290.0, 300.0 to 150.0)) {
                // Up close part of the target can be behind the camera: a partial view, kept; nothing to check there.
                val v = HalfSphere.apply(plan.viewFromPage, x, y) ?: continue
                val e = project(x, y)
                assertEquals(plan.label, e[0] - o[0], v[0] - v0[0], 1e-6)
                assertEquals(plan.label, e[1] - o[1], v[1] - v0[1], 1e-6)
            }
            assertTrue(plan.label, plan.width <= camera.width && plan.height <= camera.height)
        }
    }

    @Test
    fun `the near side of the target looks larger`() {
        val p = plans(AnticipationSettings(3, 4, 3))
        fun cut(circle: Int, meridian: Int) = p.single { it.site == HalfSphere.Site(2, circle, meridian) }
        val right = cut(3, 0)
        assertTrue(edge(right, w - 1.0, 0.0, w - 1.0, h - 1.0) > 1.3 * edge(right, 0.0, 0.0, 0.0, h - 1.0))
        val above = cut(3, 1)
        assertTrue(edge(above, 0.0, 0.0, w - 1.0, 0.0) > 1.3 * edge(above, 0.0, h - 1.0, w - 1.0, h - 1.0))
        // The nearest poles overflow the frame: each preview is the frame.
        for (near in p.filter { it.kind == HalfSphere.Kind.POLE && it.site.shell == 0 }) {
            assertEquals(near.label, camera.width to camera.height, near.width to near.height)
        }
    }

    @Test
    fun `close up, the aims tile the whole target, and steep partial views are kept`() {
        val s = AnticipationSettings(3, 8, 3)
        val p = plans(s)
        // Every reference pixel on a coarse grid is in frame in some square-on, upright view of each half-sphere.
        for (shell in 0 until 3) {
            val poles = p.filter { it.kind == HalfSphere.Kind.POLE && it.site.shell == shell && it.site.roll == 0 }
            assertEquals(s.aimsPerSide(shell).let { it * it }, poles.size)
            for (y in (2 until h - 2 step 25) + (h - 3)) for (x in (2 until w - 2 step 25) + (w - 3)) {
                assertTrue("shell $shell ($x,$y)", poles.any { pl ->
                    HalfSphere.apply(pl.viewFromPage, x.toDouble(), y.toDouble())?.let { v -> v[0] in 0.0..pl.width - 1.0 && v[1] in 0.0..pl.height - 1.0 } == true
                })
            }
        }
        // The nearest half-sphere's steepest cuts are all there, though part of the target is behind the camera.
        val steep = p.filter { it.site.shell == 0 && it.site.circle == 3 }
        assertEquals(9 * 8, steep.size)
        assertTrue(steep.any { pl -> listOf(0.0 to 0.0, w - 1.0 to 0.0, 0.0 to h - 1.0, w - 1.0 to h - 1.0).any { (x, y) -> HalfSphere.apply(pl.viewFromPage, x, y) == null } })
        // And they render.
        val ref = reference()
        for (pl in steep.take(4)) assertEquals(pl.width * pl.height, HalfSphere.render(ref, w, h, pl, useOpenCv = false).luma.size)
    }

    @Test
    fun `every preview shows each reference point where its homography puts it, and maps back`() {
        val ref = reference()
        for (plan in plans(AnticipationSettings(3, 4, 3, roll = 2))) {
            val p = HalfSphere.render(ref, w, h, plan, useOpenCv = false)
            assertEquals(plan.width * plan.height, p.luma.size)
            var checked = 0
            for (gy in 1..5) for (gx in 1..7) {
                val v = HalfSphere.apply(plan.viewFromPage, gx * 50.0, gy * 50.0) ?: continue
                if (v[0] < 2 || v[1] < 2 || v[0] > plan.width - 3 || v[1] > plan.height - 3) continue
                val px = p.luma[Math.round(v[1]).toInt() * plan.width + Math.round(v[0]).toInt()].toInt() and 0xff
                assertTrue("${plan.label} dot ($gx,$gy): $px", px > 60)
                val back = HalfSphere.apply(p.pageFromView.map { it.toDouble() }.toDoubleArray(), v[0], v[1])!!
                assertEquals(plan.label, gx * 50.0, back[0], 1e-2)
                assertEquals(plan.label, gy * 50.0, back[1], 1e-2)
                checked++
            }
            assertTrue("${plan.label}: $checked dots in view", checked >= 1)
        }
    }

    @Test
    fun `previews are built farthest first`() {
        val all = plans(AnticipationSettings(3, 8, 3, roll = 2))
        val ordered = HalfSphere.prioritized(all, AnticipationFeeder::priorityCost)
        assertEquals(all.toSet(), ordered.toSet())
        assertEquals(HalfSphere.Kind.CUT, ordered.first().kind)
        assertTrue(ordered.first().label, ordered.first().label.contains("tilt 67.5°"))
        // Two close-ups of different parts of the wall, otherwise alike, are a frame or more apart.
        val corners = all.filter { it.site.shell == 0 && it.site.circle == 0 && it.site.roll == 0 && it.site.aim in setOf(0, 8) }
        assertTrue(AnticipationFeeder.priorityCost(corners[0].pose, corners[1].pose) >= AnticipationFeeder.DEG_PER_FRAME)
    }

    private fun steepLabelled(all: List<HalfSphere.Plan>, label: String) =
        AnticipationFeeder.isSteepVertical(all.first { it.site.roll == 0 && it.site.aim == 0 && it.label.contains(label) })

    @Test
    fun `views from steeply above or below are rare, sideways and square-on ones are not`() {
        val all = plans(AnticipationSettings(3, 8, 3, roll = 2))
        assertTrue(!AnticipationFeeder.isSteepVertical(all.first { it.kind == HalfSphere.Kind.POLE }))
        // Azimuth 90 is toward the page's top (the camera above the work), 270 toward its bottom.
        assertTrue(steepLabelled(all, "tilt 67.5° toward 90°"))
        assertTrue(steepLabelled(all, "tilt 67.5° toward 270°"))
        assertTrue(steepLabelled(all, "tilt 67.5° toward 45°")) // a slant that is mostly vertical
        // Along the wall: as steep as it gets, but sideways.
        assertTrue(!steepLabelled(all, "tilt 67.5° toward 0°"))
        assertTrue(!steepLabelled(all, "tilt 67.5° toward 180°"))
        // Shallow views are common whichever way they lean.
        assertTrue(!steepLabelled(all, "tilt 22.5° toward 90°"))
        assertTrue(!steepLabelled(all, "tilt 22.5° toward 270°"))
    }

    @Test
    fun `steep vertical views are built last and none are dropped`() {
        val all = plans(AnticipationSettings(3, 8, 3, roll = 2))
        val ordered = HalfSphere.prioritized(all, AnticipationFeeder::priorityCost, AnticipationFeeder::isSteepVertical)
        assertEquals(all.size, ordered.size)
        assertEquals(all.toSet(), ordered.toSet())
        val rareCount = all.count(AnticipationFeeder::isSteepVertical)
        assertTrue("some views are rare", rareCount in 1 until all.size)
        val firstRare = ordered.indexOfFirst(AnticipationFeeder::isSteepVertical)
        assertEquals(all.size - rareCount, firstRare)
        assertTrue(ordered.drop(firstRare).all(AnticipationFeeder::isSteepVertical))
        // The common views are still built farthest first: the first is a steep sideways cut, not a rare one.
        assertEquals(HalfSphere.Kind.CUT, ordered.first().kind)
        assertTrue(ordered.first().label, ordered.first().label.contains("tilt 67.5°"))
    }

    @Test
    fun `without a rarity test the order is unchanged`() {
        val all = plans(AnticipationSettings(3, 8, 3, roll = 2))
        val plain = HalfSphere.prioritized(all, AnticipationFeeder::priorityCost)
        val explicit = HalfSphere.prioritized(all, AnticipationFeeder::priorityCost) { false }
        assertEquals(plain.map { it.label }, explicit.map { it.label })
    }
}
