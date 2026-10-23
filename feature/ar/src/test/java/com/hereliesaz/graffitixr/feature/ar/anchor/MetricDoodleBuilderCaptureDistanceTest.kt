package com.hereliesaz.graffitixr.feature.ar.anchor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetricDoodleBuilderCaptureDistanceTest {

    // GL world-to-view (column-major) of a camera at (0.2, 1.5, 0.3) looking down -z, no rotation.
    private val view = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        -0.2f, -1.5f, -0.3f, 1f,
    )

    @Test
    fun distanceToTheWallIsAlongItsNormal() {
        // Wall plane z = 0 (normal +z, any length): the camera stands 0.3 m off it.
        assertEquals(0.3, MetricDoodleBuilder.captureDistanceMeters(view, floatArrayOf(5f, 1f, 0f), floatArrayOf(0f, 0f, 2f))!!, 1e-6)
    }

    @Test
    fun aDegenerateNormalHasNoDistance() {
        assertNull(MetricDoodleBuilder.captureDistanceMeters(view, floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f)))
    }

    @Test
    fun aCloseCaptureCapsZoomByFocus() {
        // 0.3 m with a 0.10 m minimum focus (and its margin): far below the 6x the half-spheres reach.
        val cap = CloseUpFeatures.maxZoomFor(0.3)
        assert(cap < 3f) { "cap=$cap" }
    }
}
