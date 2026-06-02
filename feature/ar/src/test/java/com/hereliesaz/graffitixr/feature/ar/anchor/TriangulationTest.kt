package com.hereliesaz.graffitixr.feature.ar.anchor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TriangulationTest {
    private fun identity() = floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f)
    // World→camera view with identity rotation and camera centered at (cx,cy,cz): t = -C.
    private fun viewWithCenter(cx: Float, cy: Float, cz: Float) =
        identity().also { it[12] = -cx; it[13] = -cy; it[14] = -cz }

    @Test fun `recovers a known point from two views`() {
        val fx = 500f; val fy = 500f; val ppx = 320f; val ppy = 320f
        val p0 = Triangulation.projectionFromView(viewWithCenter(0f, 0f, 0f), fx, fy, ppx, ppy)
        val p1 = Triangulation.projectionFromView(viewWithCenter(0.2f, 0f, 0f), fx, fy, ppx, ppy)
        val x = 0.05f; val y = 0.1f; val z = 2f // off-axis, 2 m in front
        val uv0 = Triangulation.project(p0, x, y, z)!!
        val uv1 = Triangulation.project(p1, x, y, z)!!
        val r = Triangulation.triangulate(p0, p1, uv0[0], uv0[1], uv1[0], uv1[1])!!
        assertEquals(x, r[0], 1e-2f)
        assertEquals(y, r[1], 1e-2f)
        assertEquals(z, r[2], 1e-2f)
    }

    @Test fun `baseline is the camera-center distance`() {
        assertEquals(0.2f, Triangulation.baselineMeters(viewWithCenter(0f, 0f, 0f), viewWithCenter(0.2f, 0f, 0f)), 1e-4f)
    }

    @Test fun `zero baseline is rejected as degenerate`() {
        val p = Triangulation.projectionFromView(viewWithCenter(0f, 0f, 0f), 500f, 500f, 320f, 320f)
        val uv = Triangulation.project(p, 0.05f, 0.1f, 2f)!!
        assertNull(Triangulation.triangulate(p, p, uv[0], uv[1], uv[0], uv[1]))
    }
}
