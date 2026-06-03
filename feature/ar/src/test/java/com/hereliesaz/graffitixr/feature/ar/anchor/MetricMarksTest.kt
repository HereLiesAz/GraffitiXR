package com.hereliesaz.graffitixr.feature.ar.anchor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricMarksTest {
    private val fx = 500f; private val fy = 500f; private val cx = 320f; private val cy = 320f

    private fun identity() = floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f)
    // CV-convention world→camera with identity rotation, camera centered at C: t = -C.
    private fun viewWithCenter(x: Float, y: Float, z: Float) =
        identity().also { it[12] = -x; it[13] = -y; it[14] = -z }

    private fun proj(view: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        val p = Triangulation.projectionFromView(view, fx, fy, cx, cy)
        return Triangulation.project(p, x, y, z)!!
    }

    @Test fun `glViewToCv negates the Y and Z rows`() {
        val gl = floatArrayOf(1f,2f,3f,4f, 5f,6f,7f,8f, 9f,10f,11f,12f, 13f,14f,15f,16f)
        val cv = MetricMarks.glViewToCv(gl)
        for (col in 0 until 4) {
            assertEquals(gl[col*4+0], cv[col*4+0], 0f)     // row 0 unchanged
            assertEquals(-gl[col*4+1], cv[col*4+1], 0f)    // row 1 negated
            assertEquals(-gl[col*4+2], cv[col*4+2], 0f)    // row 2 negated
            assertEquals(gl[col*4+3], cv[col*4+3], 0f)     // row 3 unchanged
        }
    }

    @Test fun `recovers camera-frame points from a step-aside baseline`() {
        val v0 = viewWithCenter(0f, 0f, 0f)   // keyframe 0 at origin -> cam0 frame == world
        val v1 = viewWithCenter(0.2f, 0f, 0f) // stepped 20cm aside
        val gt = listOf(
            floatArrayOf(0.05f, 0.1f, 2f),
            floatArrayOf(-0.3f, 0.2f, 1.5f),
            floatArrayOf(0.0f, -0.1f, 3f),
        )
        val corrs = gt.map { g ->
            val a = proj(v0, g[0], g[1], g[2]); val b = proj(v1, g[0], g[1], g[2])
            MetricMarks.Corr(a[0], a[1], b[0], b[1])
        }
        val r = MetricMarks.triangulate(corrs, v0, v1, fx, fy, cx, cy)
        assertEquals(3, r.count)
        for (i in gt.indices) {
            assertEquals(gt[i][0], r.pointsCam0[i*3+0], 1e-2f)
            assertEquals(gt[i][1], r.pointsCam0[i*3+1], 1e-2f)
            assertEquals(gt[i][2], r.pointsCam0[i*3+2], 1e-2f)
        }
    }

    @Test fun `rejects a near-zero baseline as degenerate`() {
        val v = viewWithCenter(0f, 0f, 0f)
        val a = proj(v, 0.05f, 0.1f, 2f)
        val r = MetricMarks.triangulate(
            listOf(MetricMarks.Corr(a[0], a[1], a[0], a[1])), v, v, fx, fy, cx, cy)
        assertEquals(0, r.count)
    }

    @Test fun `drops a correspondence that reprojects badly (mismatch)`() {
        val v0 = viewWithCenter(0f, 0f, 0f); val v1 = viewWithCenter(0.2f, 0f, 0f)
        val good = floatArrayOf(0.05f, 0.1f, 2f)
        val a = proj(v0, good[0], good[1], good[2]); val b = proj(v1, good[0], good[1], good[2])
        val corrs = listOf(
            MetricMarks.Corr(a[0], a[1], b[0], b[1]),          // consistent
            MetricMarks.Corr(a[0], a[1], b[0] + 150f, b[1] + 150f), // view-1 pixel is a wrong match
        )
        val r = MetricMarks.triangulate(corrs, v0, v1, fx, fy, cx, cy)
        assertEquals(1, r.count)
        assertTrue("kept the consistent correspondence", r.kept[0] == 0)
    }
}
