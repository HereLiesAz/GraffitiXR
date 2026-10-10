package com.hereliesaz.graffitixr.feature.ar.util

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the sign conventions of [OverlayGyroCompensationMath] — the part of Overlay's Gyro toggle
 * that is easy to get backwards and, unlike the sensor and MiDaS plumbing around it, fully testable
 * on the JVM. Each motion test states the physical expectation: when the phone turns one way, the
 * wall (and so the design pinned to it) slides the OTHER way across the screen.
 */
class OverlayGyroCompensationMathTest {

    private val eps = 1e-3f
    private val fx = 1500f
    private val fy = 1500f
    private val cx = 540f
    private val cy = 1170f
    private val k = floatArrayOf(fx, fy, cx, cy)

    private fun rx(deg: Double): FloatArray {
        val r = Math.toRadians(deg); val c = cos(r).toFloat(); val s = sin(r).toFloat()
        return floatArrayOf(1f, 0f, 0f, 0f, c, -s, 0f, s, c)
    }

    private fun ry(deg: Double): FloatArray {
        val r = Math.toRadians(deg); val c = cos(r).toFloat(); val s = sin(r).toFloat()
        return floatArrayOf(c, 0f, s, 0f, 1f, 0f, -s, 0f, c)
    }

    private fun rz(deg: Double): FloatArray {
        val r = Math.toRadians(deg); val c = cos(r).toFloat(); val s = sin(r).toFloat()
        return floatArrayOf(c, -s, 0f, s, c, 0f, 0f, 0f, 1f)
    }

    /** Apply a row-major homography to a screen point. */
    private fun map(h: FloatArray, u: Float, v: Float): Pair<Float, Float> {
        val x = h[0] * u + h[1] * v + h[2]
        val y = h[3] * u + h[4] * v + h[5]
        val w = h[6] * u + h[7] * v + h[8]
        return Pair(x / w, y / w)
    }

    private fun within(result: OverlayGyroCompensationMath.Result): FloatArray {
        assertTrue("expected Within, was $result", result is OverlayGyroCompensationMath.Result.Within)
        return (result as OverlayGyroCompensationMath.Result.Within).homography
    }

    private fun assertClose(expected: Float, actual: Float, tolerance: Float = eps) =
        assertTrue("expected $expected, was $actual", abs(expected - actual) < tolerance)

    @Test
    fun `no rotation is the identity transform`() {
        val h = within(OverlayGyroCompensationMath.compensate(rx(0.0), 0, k))
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        for (i in 0..8) assertClose(identity[i], h[i])
    }

    @Test
    fun `turning the phone left slides the design right by f tan theta`() {
        // Phone rotates +1 deg about its own up axis (body +y = turning left). The body delta the
        // bridge reports is conj(qNow)*qRef = Ry(-1 deg).
        val h = within(OverlayGyroCompensationMath.compensate(ry(-1.0), 0, k))
        val (u, v) = map(h, cx, cy)
        assertClose(cx + fx * tan(Math.toRadians(1.0)).toFloat(), u, 0.05f)
        assertClose(cy, v, 0.05f)
    }

    @Test
    fun `tilting the phone up slides the design down`() {
        // Rotating +1 deg about body +x points the rear camera upward.
        val h = within(OverlayGyroCompensationMath.compensate(rx(-1.0), 0, k))
        val (u, v) = map(h, cx, cy)
        assertClose(cx, u, 0.05f)
        assertClose(cy + fy * tan(Math.toRadians(1.0)).toFloat(), v, 0.05f)
    }

    @Test
    fun `rolling the phone counter-clockwise turns the design clockwise on screen`() {
        // +1 deg about body +z (out of the screen) is a counter-clockwise roll as the user sees it.
        val h = within(OverlayGyroCompensationMath.compensate(rz(-1.0), 0, k))
        // The principal point is the roll's fixed point...
        val (u0, v0) = map(h, cx, cy)
        assertClose(cx, u0, 0.05f)
        assertClose(cy, v0, 0.05f)
        // ...and a point to its right moves DOWN the screen (clockwise in y-down screen space).
        val (u, v) = map(h, cx + 100f, cy)
        assertTrue("v should increase, was $v", v > cy + 1f)
        assertTrue("u should stay near its x, was $u", abs(u - (cx + 100f)) < 0.1f)
    }

    @Test
    fun `display rotation remaps body axes so left stays left in landscape`() {
        // ROTATION_90 is the device turned 90 deg counter-clockwise (Display.getRotation's own
        // example), so its right edge — body +x — is now the display's up axis. "Turning left" is
        // +1 deg about body +x: body delta Rx(-1 deg). It must produce the same rightward slide as
        // the portrait case.
        val h = within(OverlayGyroCompensationMath.compensate(rx(-1.0), 90, k))
        val (u, v) = map(h, cx, cy)
        assertClose(cx + fx * tan(Math.toRadians(1.0)).toFloat(), u, 0.05f)
        assertClose(cy, v, 0.05f)
    }

    @Test
    fun `ROTATION_270 turning left is a rotation about body minus x`() {
        // Device turned clockwise: its LEFT edge (body -x) is now up.
        val h = within(OverlayGyroCompensationMath.compensate(rx(1.0), 270, k))
        val (u, v) = map(h, cx, cy)
        assertClose(cx + fx * tan(Math.toRadians(1.0)).toFloat(), u, 0.05f)
        assertClose(cy, v, 0.05f)
    }

    /**
     * Independent model of `SensorManager.remapCoordinateSystem(inR, X, Y, outR)` as AOSP implements
     * it (`outR = inR · r`): argument X names the NEW axis (and sign) the device x axis lands on, Y the
     * same for device y, and device z completes the right-handed frame. Returned as the row-major
     * `v_new = M · v_device` component map. Written from that rule, not from RotationDeltaMath.
     */
    private fun remapLikeSensorManager(axisX: Int, axisY: Int): FloatArray {
        val m = FloatArray(9)
        fun place(axis: Int, deviceCol: Int) {
            val sign = if ((axis and 0x80) != 0) -1f else 1f
            val newRow = (axis and 0x3) - 1
            m[newRow * 3 + deviceCol] = sign
        }
        place(axisX, 0)
        place(axisY, 1)
        // Column 2 (device z in new coords) = column 0 × column 1.
        val c0 = floatArrayOf(m[0], m[3], m[6])
        val c1 = floatArrayOf(m[1], m[4], m[7])
        m[2] = c0[1] * c1[2] - c0[2] * c1[1]
        m[5] = c0[2] * c1[0] - c0[0] * c1[2]
        m[8] = c0[0] * c1[1] - c0[1] * c1[0]
        return m
    }

    @Test
    fun `bodyToDisplay matches remapCoordinateSystem for every Surface rotation`() {
        // SensorManager.AXIS_X/Y = 1/2, AXIS_MINUS_* = 0x80 | axis; the standard per-rotation table.
        val axisX = 1; val axisY = 2; val minusX = 0x81; val minusY = 0x82
        val table = mapOf(
            0 to remapLikeSensorManager(axisX, axisY),
            90 to remapLikeSensorManager(axisY, minusX),
            180 to remapLikeSensorManager(minusX, minusY),
            270 to remapLikeSensorManager(minusY, axisX),
        )
        for ((deg, expected) in table) {
            val actual = OverlayGyroCompensationMath.bodyToDisplay(deg)
            for (i in 0..8) assertClose(expected[i], actual[i])
        }
        // Spelled out for 90: screen x = -body y, screen y = body x.
        val v90 = RotationDeltaMath.multiplyMat3Vec3(
            OverlayGyroCompensationMath.bodyToDisplay(90), floatArrayOf(1f, 2f, 3f),
        )
        assertClose(-2f, v90[0]); assertClose(1f, v90[1]); assertClose(3f, v90[2])
        // ...and it is RotationDeltaMath.rotationAboutZ(+90), not the negated angle.
        val rz90 = RotationDeltaMath.rotationAboutZ(90)
        for (i in 0..8) assertClose(rz90[i], table.getValue(90)[i])
    }

    @Test
    fun `rotation past the release threshold is reported, not compensated`() {
        val result = OverlayGyroCompensationMath.compensate(ry(-5.0), 0, k)
        assertTrue(result is OverlayGyroCompensationMath.Result.Exceeded)
        assertClose(5f, (result as OverlayGyroCompensationMath.Result.Exceeded).angleDegrees, 0.01f)
    }

    @Test
    fun `rotation angle is read from the trace`() {
        assertClose(2f, OverlayGyroCompensationMath.rotationAngleDegrees(rz(2.0)), 0.01f)
        assertClose(0f, OverlayGyroCompensationMath.rotationAngleDegrees(rz(0.0)), 0.01f)
    }

    @Test
    fun `translation over depth adds a parallax shift only when supplied`() {
        val h = OverlayGyroCompensationMath.homography(rx(0.0), fx, fy, cx, cy, floatArrayOf(0.01f, 0f, 0f))
        val (u, v) = map(h, cx, cy)
        assertClose(cx + fx * 0.01f, u, 0.05f)
        assertClose(cy, v, 0.05f)
    }

    @Test
    fun `screen intrinsics swap axes and scale for a portrait FIT_CENTER view`() {
        // 4:3 landscape sensor frame shown in a 1080x2340 portrait view: shown as 3000x4000,
        // FIT_CENTER scale = min(1080/3000, 2340/4000) = 0.36.
        val s = OverlayGyroCompensationMath.screenIntrinsics(3200f, 3100f, 4000, 3000, 1080, 2340)
        assertClose(3100f * 0.36f, s[0], 0.01f)
        assertClose(3200f * 0.36f, s[1], 0.01f)
        assertClose(540f, s[2]); assertClose(1170f, s[3])
    }

    @Test
    fun `screen intrinsics keep axes for a landscape view`() {
        val s = OverlayGyroCompensationMath.screenIntrinsics(3200f, 3100f, 4000, 3000, 2340, 1080)
        // scale = min(2340/4000, 1080/3000) = 0.36
        assertClose(3200f * 0.36f, s[0], 0.01f)
        assertClose(3100f * 0.36f, s[1], 0.01f)
    }

    @Test
    fun `fallback intrinsics are positive and centred`() {
        val s = OverlayGyroCompensationMath.fallbackScreenIntrinsics(1080, 2340)
        assertTrue(s[0] > 0f && s[1] > 0f)
        assertEquals(540f, s[2], 0f)
        assertEquals(1170f, s[3], 0f)
    }

    @Test
    fun `relative inverse depth compares the patch to the scene median`() {
        val w = 10
        val h = 10
        val data = FloatArray(w * h) { 1f }
        // A nearer (larger inverse depth) block around the centre.
        for (y in 3..6) for (x in 3..6) data[y * w + x] = 2f
        val r = OverlayGyroCompensationMath.relativeInverseDepthAt(data, w, h, 0.5f, 0.5f, radius = 1)
        assertClose(2f, r!!)
        assertNull(OverlayGyroCompensationMath.relativeInverseDepthAt(FloatArray(0), 0, 0, 0.5f, 0.5f))
    }
}
