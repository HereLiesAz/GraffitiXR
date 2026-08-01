package com.hereliesaz.graffitixr.feature.ar.anchor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * `marksCentroidLocal` is what puts the AR overlay on the marks instead of the screen-center anchor,
 * and it was previously unassertable: it went through `android.opengl.Matrix`, which under this
 * module's `unitTests.isReturnDefaultValues = true` returns zeros without throwing, so any test of it
 * would have agreed with any implementation.
 *
 * The fixtures below are built with FORWARD transforms only — pick the answer, push it out through
 * the anchor and then the view — so the test never re-implements the two inverses it is checking.
 */
class MetricFingerprintBuilderCentroidTest {

    private fun identity() = floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f)

    /** Rotation of [deg] about an arbitrary (normalized) axis — deliberately not an axis-aligned one. */
    private fun rigid(ax: Float, ay: Float, az: Float, deg: Float, tx: Float, ty: Float, tz: Float): FloatArray {
        val n = sqrt(ax * ax + ay * ay + az * az)
        val x = ax / n; val y = ay / n; val z = az / n
        val c = cos(Math.toRadians(deg.toDouble())).toFloat()
        val s = sin(Math.toRadians(deg.toDouble())).toFloat()
        val t = 1f - c
        return floatArrayOf(
            t*x*x + c,   t*x*y + s*z, t*x*z - s*y, 0f,
            t*x*y - s*z, t*y*y + c,   t*y*z + s*x, 0f,
            t*x*z + s*y, t*y*z - s*x, t*z*z + c,   0f,
            tx, ty, tz, 1f,
        )
    }

    /** Forward apply of a column-major 4x4 to a point (w=1). */
    private fun apply(m: FloatArray, p: FloatArray) = floatArrayOf(
        m[0] * p[0] + m[4] * p[1] + m[8] * p[2] + m[12],
        m[1] * p[0] + m[5] * p[1] + m[9] * p[2] + m[13],
        m[2] * p[0] + m[6] * p[1] + m[10] * p[2] + m[14],
    )

    // Non-identity, non-axis-aligned on both sides: with an axis-aligned rotation or an identity
    // anchor, dropping either inverse (or swapping the two) can still land on the right answer.
    private val anchor = rigid(0.3f, -0.7f, 0.6f, 37f, 1.5f, -2f, 4f)
    private val view = rigid(-0.2f, 0.5f, 0.84f, -63f, 0.4f, 1.1f, -0.9f)

    @Test fun `returns the camera-frame mean expressed in the anchor's local frame`() {
        // The answer, chosen first. Everything below is derived from it by forward transforms.
        val expectedLocal = floatArrayOf(0.3f, -0.45f, 0.7f)
        val world = apply(anchor, expectedLocal)
        val camMean = apply(view, world)

        // Four points whose mean is exactly camMean, so the averaging step is exercised without
        // changing the expected answer.
        val offsets = listOf(
            floatArrayOf(0.11f, -0.23f, 0.34f), floatArrayOf(-0.11f, 0.23f, -0.34f),
            floatArrayOf(-0.42f, 0.07f, 0.19f), floatArrayOf(0.42f, -0.07f, -0.19f),
        )
        val pointsCam = FloatArray(offsets.size * 3)
        offsets.forEachIndexed { i, o ->
            pointsCam[i * 3] = camMean[0] + o[0]
            pointsCam[i * 3 + 1] = camMean[1] + o[1]
            pointsCam[i * 3 + 2] = camMean[2] + o[2]
        }

        val got = MetricFingerprintBuilder.marksCentroidLocal(pointsCam, view, anchor)!!
        assertEquals(expectedLocal[0], got[0], 1e-3f)
        assertEquals(expectedLocal[1], got[1], 1e-3f)
        assertEquals(expectedLocal[2], got[2], 1e-3f)
    }

    /**
     * With an identity anchor the result must be the plain world-space mean, which pins the view
     * inverse on its own — the case where the anchor factor cannot mask a mistake in it.
     */
    @Test fun `identity anchor leaves the world-space mean`() {
        val world = floatArrayOf(-0.8f, 0.25f, 2.3f)
        val cam = apply(view, world)
        val got = MetricFingerprintBuilder.marksCentroidLocal(cam, view, identity())!!
        assertEquals(world[0], got[0], 1e-3f)
        assertEquals(world[1], got[1], 1e-3f)
        assertEquals(world[2], got[2], 1e-3f)
    }

    @Test fun `no points yields null`() {
        assertNull(MetricFingerprintBuilder.marksCentroidLocal(FloatArray(0), view, anchor))
    }

    /**
     * The guard that replaced `Matrix.invertM`'s boolean return. rigidInverse cannot report
     * non-invertibility, so a zeroed pose (an anchor whose transform was never written) has to be
     * rejected up front or the overlay gets centered on garbage instead of not being re-centered.
     */
    @Test fun `degenerate pose yields null instead of garbage`() {
        val points = floatArrayOf(0f, 0f, 2f)
        assertNull(MetricFingerprintBuilder.marksCentroidLocal(points, FloatArray(16), anchor))
        assertNull(MetricFingerprintBuilder.marksCentroidLocal(points, view, FloatArray(16)))

        // A scaled (non-rigid) matrix would come back off by s² from rigidInverse, so it is refused too.
        val scaled = anchor.copyOf().also { for (c in 0 until 3) for (r in 0 until 3) it[c * 4 + r] *= 2f }
        assertNull(MetricFingerprintBuilder.marksCentroidLocal(points, view, scaled))

        // A column zeroed by a half-written pose is singular — exactly what invertM used to catch.
        val singular = anchor.copyOf().also { it[8] = 0f; it[9] = 0f; it[10] = 0f }
        assertNull(MetricFingerprintBuilder.marksCentroidLocal(points, view, singular))

        val notFinite = anchor.copyOf().also { it[12] = Float.NaN }
        assertNull(MetricFingerprintBuilder.marksCentroidLocal(points, view, notFinite))
    }
}
