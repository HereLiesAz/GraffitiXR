package com.hereliesaz.graffitixr.feature.ar.rendering

import com.hereliesaz.graffitixr.common.sensor.CameraIntrinsics
import kotlin.math.abs
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Closes the loop [ProjectionMatrix]'s doc derives: project a known point through the built
 * matrix, perspective-divide, convert NDC to pixels, and check it lands where the pinhole formula
 * says it should — independently computed, not by re-deriving the same matrix. Mirrors how
 * `CaptureRotationTest` closes the equivalent loop for `CaptureRotation`'s pixel-rotation formula.
 */
class ProjectionMatrixTest {

    private val intrinsics = CameraIntrinsics(fx = 1000f, fy = 1100f, cx = 640f, cy = 360f, width = 1280, height = 720)

    /** Column-major 4x4 * (x,y,z,1). */
    private fun transform(m: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        val v = floatArrayOf(x, y, z, 1f)
        val out = FloatArray(4)
        for (row in 0..3) {
            var sum = 0f
            for (col in 0..3) sum += m[col * 4 + row] * v[col]
            out[row] = sum
        }
        return out
    }

    /** GL eye-space (x, y, z<0) -> pixel (u, v), via the projection matrix + perspective divide. */
    private fun projectToPixel(m: FloatArray, x: Float, y: Float, z: Float): Pair<Float, Float> {
        val clip = transform(m, x, y, z)
        val ndcX = clip[0] / clip[3]
        val ndcY = clip[1] / clip[3]
        val u = (ndcX + 1f) / 2f * intrinsics.width
        val v = (1f - ndcY) / 2f * intrinsics.height
        return u to v
    }

    /** The same point's pixel position via the direct pinhole formula, independent of the matrix. */
    private fun pinholePixel(xg: Float, yg: Float, zg: Float): Pair<Float, Float> {
        val xc = xg; val yc = -yg; val zc = -zg // OpenGL eye space -> OpenCV camera space
        val u = intrinsics.fx * xc / zc + intrinsics.cx
        val v = intrinsics.fy * yc / zc + intrinsics.cy
        return u to v
    }

    @Test
    fun `a point on the optical axis projects to the principal point`() {
        val m = ProjectionMatrix.buildFrom(intrinsics)
        val (u, v) = projectToPixel(m, 0f, 0f, -5f)
        assertTrue(abs(u - intrinsics.cx) < 1e-2f)
        assertTrue(abs(v - intrinsics.cy) < 1e-2f)
    }

    @Test
    fun `projected pixel matches the independent pinhole formula for an off-axis point`() {
        val m = ProjectionMatrix.buildFrom(intrinsics)
        for ((x, y, z) in listOf(
            Triple(0.3f, 0.2f, -2.5f),
            Triple(-1.1f, 0.6f, -4.0f),
            Triple(0.05f, -0.4f, -1.2f),
        )) {
            val (u, v) = projectToPixel(m, x, y, z)
            val (eu, ev) = pinholePixel(x, y, z)
            assertTrue("u: expected $eu, got $u", abs(u - eu) < 1e-2f)
            assertTrue("v: expected $ev, got $v", abs(v - ev) < 1e-2f)
        }
    }

    @Test
    fun `near plane maps to NDC z of -1 and far plane to +1`() {
        val near = 0.1f
        val far = 20f
        val m = ProjectionMatrix.buildFrom(intrinsics, near = near, far = far)

        val atNear = transform(m, 0f, 0f, -near)
        assertTrue(abs(atNear[2] / atNear[3] - (-1f)) < 1e-3f)

        val atFar = transform(m, 0f, 0f, -far)
        assertTrue(abs(atFar[2] / atFar[3] - 1f) < 1e-3f)
    }

    @Test
    fun `rejects a non-positive image size`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProjectionMatrix.buildFrom(intrinsics.copy(width = 0))
        }
    }

    @Test
    fun `rejects a degenerate or inverted near-far range`() {
        assertThrows(IllegalArgumentException::class.java) { ProjectionMatrix.buildFrom(intrinsics, near = 5f, far = 1f) }
        assertThrows(IllegalArgumentException::class.java) { ProjectionMatrix.buildFrom(intrinsics, near = 0f, far = 1f) }
    }
}
