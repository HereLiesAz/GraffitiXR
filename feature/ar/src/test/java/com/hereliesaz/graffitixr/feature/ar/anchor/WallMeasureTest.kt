package com.hereliesaz.graffitixr.feature.ar.anchor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * Expected values come from FORWARD projection (wall-local → world → clip → screen) through plain
 * matrix products, then the code under test must invert that path. The test never calls the
 * unprojection formula it is checking.
 */
class WallMeasureTest {

    // Standard GL perspective, fovY 60°, aspect 0.5 (portrait), near 0.1, far 100 — column-major.
    private val proj: FloatArray = run {
        val f = (1.0 / kotlin.math.tan(Math.toRadians(30.0))).toFloat()
        val aspect = 0.5f; val n = 0.1f; val fa = 100f
        floatArrayOf(
            f / aspect, 0f, 0f, 0f,
            0f, f, 0f, 0f,
            0f, 0f, (fa + n) / (n - fa), -1f,
            0f, 0f, 2f * fa * n / (n - fa), 0f,
        )
    }

    private fun mul(a: FloatArray, v: FloatArray): FloatArray = FloatArray(4) { r ->
        a[r] * v[0] + a[4 + r] * v[1] + a[8 + r] * v[2] + a[12 + r] * v[3]
    }

    /** Forward: wall-local (x, y, 0) → normalised screen (nx, ny), y down. */
    private fun toScreen(local: FloatArray, wall: FloatArray, view: FloatArray): FloatArray {
        val world = mul(wall, floatArrayOf(local[0], local[1], 0f, 1f))
        val clip = mul(proj, mul(view, world))
        val ndcX = clip[0] / clip[3]; val ndcY = clip[1] / clip[3]
        return floatArrayOf((ndcX + 1f) / 2f, (1f - ndcY) / 2f)
    }

    private fun translation(x: Float, y: Float, z: Float) =
        floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, x,y,z,1f)

    /** Rotation about Y by [deg], then translation. Column-major. */
    private fun yawThenTranslate(deg: Float, x: Float, y: Float, z: Float): FloatArray {
        val r = Math.toRadians(deg.toDouble()); val c = cos(r).toFloat(); val s = sin(r).toFloat()
        return floatArrayOf(c,0f,-s,0f, 0f,1f,0f,0f, s,0f,c,0f, x,y,z,1f)
    }

    private fun measure(local: FloatArray, wall: FloatArray, view: FloatArray): FloatArray? {
        val n = toScreen(local, wall, view)
        val ray = WallMeasure.screenRay(n[0], n[1], view, proj) ?: return null
        return WallMeasure.intersectWallLocal(ray, wall)
    }

    @Test
    fun `facing wall recovers wall-local points`() {
        val wall = translation(0f, 0f, -3f)          // wall 3 m ahead, normal toward camera
        val view = translation(0f, 0f, 0f)           // camera at origin looking -Z
        val a = measure(floatArrayOf(-0.6f, 0.2f), wall, view)!!
        assertEquals(-0.6f, a[0], 1e-3f); assertEquals(0.2f, a[1], 1e-3f)
    }

    @Test
    fun `oblique wall and moved camera still recover points and the width`() {
        val wall = yawThenTranslate(35f, 0.4f, -0.2f, -4f)
        // view = inverse(camera pose); camera at (0.3, 0.1, 0.5) yawed -10°.
        val view = PoseMath.rigidInverse(yawThenTranslate(-10f, 0.3f, 0.1f, 0.5f))
        val a = measure(floatArrayOf(-1.2f, 0.3f), wall, view)!!
        val b = measure(floatArrayOf(0.9f, -0.1f), wall, view)!!
        assertEquals(-1.2f, a[0], 2e-3f); assertEquals(0.3f, a[1], 2e-3f)
        assertEquals(0.9f, b[0], 2e-3f); assertEquals(-0.1f, b[1], 2e-3f)
        // |(2.1, -0.4)| = 2.137756…
        assertEquals(2.13776f, WallMeasure.widthMeters(a, b)!!, 5e-3f)
    }

    @Test
    fun `same rigid offset applied to camera and wall changes nothing`() {
        val wall = translation(0f, 0f, -3f)
        val view = translation(0f, 0f, 0f)
        val g = yawThenTranslate(50f, 7f, -2f, 11f)  // an ARCore world rebase
        val wallG = PoseMath.multiply(g, wall)
        val viewG = PoseMath.multiply(view, PoseMath.rigidInverse(g))
        val p = measure(floatArrayOf(0.5f, -0.4f), wallG, viewG)!!
        assertEquals(0.5f, p[0], 1e-3f); assertEquals(-0.4f, p[1], 1e-3f)
    }

    @Test
    fun `a ray parallel to the wall is refused`() {
        // Wall plane is horizontal (normal +Y); the camera looks along -Z, so the centre ray is parallel.
        val wall = floatArrayOf(1f,0f,0f,0f, 0f,0f,-1f,0f, 0f,1f,0f,0f, 0f,-1f,-3f,1f)
        val view = translation(0f, 0f, 0f)
        val ray = WallMeasure.screenRay(0.5f, 0.5f, view, proj)!!
        assertNull(WallMeasure.intersectWallLocal(ray, wall))
    }

    @Test
    fun `a wall behind the camera is refused`() {
        val ray = WallMeasure.screenRay(0.5f, 0.5f, translation(0f, 0f, 0f), proj)!!
        assertNull(WallMeasure.intersectWallLocal(ray, translation(0f, 0f, 3f)))
    }

    @Test
    fun `hits outside the range bounds are refused`() {
        val view = translation(0f, 0f, 0f)
        val ray = WallMeasure.screenRay(0.5f, 0.5f, view, proj)!!
        assertNull(WallMeasure.intersectWallLocal(ray, translation(0f, 0f, -0.05f)))
        assertNull(WallMeasure.intersectWallLocal(ray, translation(0f, 0f, -12f)))
        assertNotNull(WallMeasure.intersectWallLocal(ray, translation(0f, 0f, -9.9f)))
    }

    @Test
    fun `a scaled wall frame is refused, not mis-measured`() {
        val scaled = translation(0f, 0f, -3f).also { it[0] = 2f; it[5] = 2f; it[10] = 2f }
        val ray = WallMeasure.screenRay(0.5f, 0.5f, translation(0f, 0f, 0f), proj)!!
        assertNull(WallMeasure.intersectWallLocal(ray, scaled))
    }

    @Test
    fun `width bounds`() {
        assertNull(WallMeasure.widthMeters(floatArrayOf(0f, 0f), floatArrayOf(0.04f, 0f)))
        assertEquals(0.06f, WallMeasure.widthMeters(floatArrayOf(0f, 0f), floatArrayOf(0.06f, 0f))!!, 1e-6f)
        assertEquals(5f, WallMeasure.widthMeters(floatArrayOf(0f, 0f), floatArrayOf(3f, 4f))!!, 1e-6f)
        assertNull(WallMeasure.widthMeters(floatArrayOf(0f, 0f), floatArrayOf(101f, 0f)))
        assertNull(WallMeasure.widthMeters(floatArrayOf(0f, Float.NaN), floatArrayOf(1f, 0f)))
    }

    @Test
    fun `orthographic projection is refused`() {
        val ortho = floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,-1f,0f, 0f,0f,0f,1f)
        assertNull(WallMeasure.screenRay(0.5f, 0.5f, translation(0f, 0f, 0f), ortho))
    }

    @Test
    fun `off-centre principal point is honoured`() {
        // ARCore projections carry a principal-point offset in proj[8]/proj[9].
        val offset = proj.copyOf().also { it[8] = 0.07f; it[9] = -0.05f }
        val wall = yawThenTranslate(20f, 0.1f, 0.3f, -2.5f)
        val view = translation(0f, 0f, 0f)
        val local = floatArrayOf(0.7f, -0.45f)
        val world = mul(wall, floatArrayOf(local[0], local[1], 0f, 1f))
        val clip = mul(offset, mul(view, world))
        val n = floatArrayOf((clip[0] / clip[3] + 1f) / 2f, (1f - clip[1] / clip[3]) / 2f)
        val ray = WallMeasure.screenRay(n[0], n[1], view, offset)!!
        val p = WallMeasure.intersectWallLocal(ray, wall)!!
        assertEquals(0.7f, p[0], 2e-3f); assertEquals(-0.45f, p[1], 2e-3f)
    }

    @Test
    fun `obliquity just inside the limit measures, just past it is refused`() {
        val view = translation(0f, 0f, 0f)
        val ray = WallMeasure.screenRay(0.5f, 0.5f, view, proj)!!   // straight down -Z
        // Wall turned 74° / 76° about Y: ray-to-normal angle equals the turn.
        assertNotNull(WallMeasure.intersectWallLocal(ray, yawThenTranslate(74f, 0f, 0f, -2f)))
        assertNull(WallMeasure.intersectWallLocal(ray, yawThenTranslate(76f, 0f, 0f, -2f)))
    }

    @Test
    fun `points kept in an unmoving frame ignore a drawn-frame correction between taps`() {
        val view = translation(0f, 0f, 0f)
        val drawnBefore = translation(0f, 0f, -3f)
        // Fusion nudges the drawn frame 8 cm sideways (in-plane) between the taps.
        val drawnAfter = translation(0.08f, 0f, -3f)
        val backbone = translation(0.2f, -0.1f, -3f) // unfused anchor: does not move
        fun tapAt(screen: FloatArray, drawn: FloatArray): FloatArray {
            val ray = WallMeasure.screenRay(screen[0], screen[1], view, proj)!!
            return WallMeasure.toFrameLocal(WallMeasure.intersectWallWorld(ray, drawn)!!, backbone)!!
        }
        // The same two physical spots on the wall plane (x = -1 and x = +1 at z = -3).
        val left = toScreen(floatArrayOf(-1f, 0f), drawnBefore, view)
        val right = toScreen(floatArrayOf(1f, 0f), drawnBefore, view)
        val w = WallMeasure.widthMeters(tapAt(left, drawnBefore), tapAt(right, drawnAfter))!!
        assertEquals(2f, w, 2e-3f)
    }

    @Test
    fun `three-component width uses depth difference`() {
        assertEquals(5f, WallMeasure.widthMeters(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 3f, 4f))!!, 1e-6f)
    }

    @Test
    fun `second tap on the first tap's frozen plane ignores a drawn-frame re-orientation`() {
        val view = translation(0f, 0f, 0f)
        val backbone = translation(0.2f, -0.1f, -3f)   // unfused anchor: does not move
        val drawnBefore = translation(0f, 0f, -3f)     // the wall: z = -3, facing the camera
        // Fusion yaws the drawn frame 10° about its origin between the taps.
        val drawnAfter = PoseMath.multiply(drawnBefore, yawThenTranslate(10f, 0f, 0f, 0f))
        // Plane frozen at tap 1, the way the renderer stores it: inv(backbone) · drawn.
        val planeLocal = PoseMath.multiply(PoseMath.rigidInverse(backbone), drawnBefore)
        fun tap(screen: FloatArray, planeWorld: FloatArray): FloatArray {
            val ray = WallMeasure.screenRay(screen[0], screen[1], view, proj)!!
            return WallMeasure.toFrameLocal(WallMeasure.intersectWallWorld(ray, planeWorld)!!, backbone)!!
        }
        val left = toScreen(floatArrayOf(-1f, 0f), drawnBefore, view)
        val right = toScreen(floatArrayOf(1.5f, 0f), drawnBefore, view)
        val a = tap(left, PoseMath.multiply(backbone, planeLocal))
        val frozen = tap(right, PoseMath.multiply(backbone, planeLocal))
        val unfrozen = tap(right, drawnAfter)
        assertEquals(2.5f, WallMeasure.widthMeters(a, frozen)!!, 2e-3f)
        // Control: re-reading the re-oriented drawn plane would have moved the second point.
        val drift = WallMeasure.widthMeters(a, unfrozen)!! - 2.5f
        org.junit.Assert.assertTrue("re-oriented plane should change the width, drift=$drift", kotlin.math.abs(drift) > 0.05f)
    }
}
