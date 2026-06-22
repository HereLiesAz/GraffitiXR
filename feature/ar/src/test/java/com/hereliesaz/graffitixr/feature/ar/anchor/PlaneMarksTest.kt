package com.hereliesaz.graffitixr.feature.ar.anchor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaneMarksTest {
    private val fx = 500f; private val fy = 500f; private val cx = 320f; private val cy = 320f

    private fun identity() = floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f)
    // CV-convention world→camera with identity rotation, camera centred at C: t = -C.
    private fun viewWithCenter(x: Float, y: Float, z: Float) =
        identity().also { it[12] = -x; it[13] = -y; it[14] = -z }

    /** Pixel that a CV-frame point (x,y,z) projects to under these intrinsics (camera at origin). */
    private fun pixelOf(x: Float, y: Float, z: Float) =
        PlaneMarks.Pixel(fx * x / z + cx, fy * y / z + cy)

    @Test fun `back-projects center pixel onto a fronto-parallel wall at the plane depth`() {
        // Wall at z = 2 m, normal facing the camera. Centre pixel -> ray (0,0,1) -> hit at z = 2.
        val r = PlaneMarks.backProject(
            pixels = listOf(PlaneMarks.Pixel(cx, cy)),
            cvView = identity(),
            planePointWorld = floatArrayOf(0f, 0f, 2f),
            planeNormalWorld = floatArrayOf(0f, 0f, -1f),
            fx = fx, fy = fy, cx = cx, cy = cy,
        )
        assertEquals(1, r.count)
        assertEquals(0f, r.pointsCam[0], 1e-4f)
        assertEquals(0f, r.pointsCam[1], 1e-4f)
        assertEquals(2f, r.pointsCam[2], 1e-4f)
    }

    @Test fun `recovers the exact 3D points the pixels came from`() {
        // Ground-truth points on a fronto-parallel wall at z = 2.5; project them, then back-project.
        val gt = listOf(
            floatArrayOf(0.0f, 0.0f, 2.5f),
            floatArrayOf(0.4f, -0.3f, 2.5f),
            floatArrayOf(-0.5f, 0.2f, 2.5f),
        )
        val pixels = gt.map { pixelOf(it[0], it[1], it[2]) }
        val r = PlaneMarks.backProject(
            pixels = pixels,
            cvView = identity(),
            planePointWorld = floatArrayOf(0f, 0f, 2.5f),
            planeNormalWorld = floatArrayOf(0f, 0f, -1f),
            fx = fx, fy = fy, cx = cx, cy = cy,
        )
        assertEquals(3, r.count)
        for (i in gt.indices) {
            assertEquals(gt[i][0], r.pointsCam[3 * i + 0], 1e-3f)
            assertEquals(gt[i][1], r.pointsCam[3 * i + 1], 1e-3f)
            assertEquals(gt[i][2], r.pointsCam[3 * i + 2], 1e-3f)
        }
    }

    @Test fun `works when the camera is translated in world space`() {
        // Camera stepped 1 m to the side; a wall point sits at world (1, 0, 3). In the camera frame
        // that's (0, 0, 3), so the centre pixel must back-project to depth 3.
        val view = viewWithCenter(1f, 0f, 0f)
        val r = PlaneMarks.backProject(
            pixels = listOf(PlaneMarks.Pixel(cx, cy)),
            cvView = view,
            planePointWorld = floatArrayOf(1f, 0f, 3f), // a point on the wall, in world
            planeNormalWorld = floatArrayOf(0f, 0f, -1f),
            fx = fx, fy = fy, cx = cx, cy = cy,
        )
        assertEquals(1, r.count)
        assertEquals(0f, r.pointsCam[0], 1e-4f)
        assertEquals(0f, r.pointsCam[1], 1e-4f)
        assertEquals(3f, r.pointsCam[2], 1e-4f)
    }

    @Test fun `drops rays parallel to the plane and hits behind the camera`() {
        // Plane parallel to the viewing direction (normal along camera X): the centre ray never hits.
        val parallel = PlaneMarks.backProject(
            pixels = listOf(PlaneMarks.Pixel(cx, cy)),
            cvView = identity(),
            planePointWorld = floatArrayOf(2f, 0f, 0f),
            planeNormalWorld = floatArrayOf(1f, 0f, 0f), // perpendicular to ray (0,0,1)
            fx = fx, fy = fy, cx = cx, cy = cy,
        )
        assertEquals(0, parallel.count)

        // Plane behind the camera (z = -2): intersection has t < 0 → dropped.
        val behind = PlaneMarks.backProject(
            pixels = listOf(PlaneMarks.Pixel(cx, cy)),
            cvView = identity(),
            planePointWorld = floatArrayOf(0f, 0f, -2f),
            planeNormalWorld = floatArrayOf(0f, 0f, -1f),
            fx = fx, fy = fy, cx = cx, cy = cy,
        )
        assertEquals(0, behind.count)
    }

    @Test fun `enforces the depth window`() {
        val tooFar = PlaneMarks.backProject(
            pixels = listOf(PlaneMarks.Pixel(cx, cy)),
            cvView = identity(),
            planePointWorld = floatArrayOf(0f, 0f, 50f),
            planeNormalWorld = floatArrayOf(0f, 0f, -1f),
            fx = fx, fy = fy, cx = cx, cy = cy,
            maxDepthM = 10f,
        )
        assertTrue(tooFar.count == 0)
    }
}
