package com.hereliesaz.graffitixr.feature.ar.anchor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hereliesaz.sphereslam.reloc.MetricMarks

/** Two-view mark triangulation — SphereSLAM's `reloc.MetricMarks.triangulateTwoView`. */
class MetricMarksTest {
    private val fx = 500f; private val fy = 500f; private val cx = 320f; private val cy = 320f

    private fun identity() = floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f)
    // CV-convention world→camera with identity rotation, camera centered at C: t = -C.
    private fun viewWithCenter(x: Float, y: Float, z: Float) =
        identity().also { it[12] = -x; it[13] = -y; it[14] = -z }

    /** Pinhole projection of world point (x,y,z) through a column-major CV world→camera [view]. */
    private fun proj(view: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        val xc = view[0] * x + view[4] * y + view[8] * z + view[12]
        val yc = view[1] * x + view[5] * y + view[9] * z + view[13]
        val zc = view[2] * x + view[6] * y + view[10] * z + view[14]
        return floatArrayOf(fx * xc / zc + cx, fy * yc / zc + cy)
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
        val r = MetricMarks.triangulateTwoView(corrs, v0, v1, fx, fy, cx, cy)
        assertEquals(3, r.count)
        for (i in gt.indices) {
            assertEquals(gt[i][0], r.points[i*3+0], 1e-2f)
            assertEquals(gt[i][1], r.points[i*3+1], 1e-2f)
            assertEquals(gt[i][2], r.points[i*3+2], 1e-2f)
        }
    }

    @Test fun `rejects a near-zero baseline as degenerate`() {
        val v = viewWithCenter(0f, 0f, 0f)
        val a = proj(v, 0.05f, 0.1f, 2f)
        val r = MetricMarks.triangulateTwoView(
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
        val r = MetricMarks.triangulateTwoView(corrs, v0, v1, fx, fy, cx, cy)
        assertEquals(1, r.count)
        assertTrue("kept the consistent correspondence", r.kept[0] == 0)
    }
}
