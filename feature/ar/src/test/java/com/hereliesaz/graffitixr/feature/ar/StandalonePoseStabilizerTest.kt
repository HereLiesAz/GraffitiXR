// FILE: feature/ar/src/test/java/com/hereliesaz/graffitixr/feature/ar/StandalonePoseStabilizerTest.kt
package com.hereliesaz.graffitixr.feature.ar

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [StandalonePoseStabilizer]'s contract: snap on the first frame and across large moves, damp
 * small frame-to-frame jitter, and forget state on [StandalonePoseStabilizer.reset]. Thresholds come
 * from PoseFusion.diverged (0.20 m / 15°); the stabilizer must not lag real motion past them.
 */
class StandalonePoseStabilizerTest {

    private val eps = 1e-4f

    /** Column-major GL view matrix: identity rotation, translation (tx,ty,tz). */
    private fun view(tx: Float, ty: Float = 0f, tz: Float = 0f) = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        tx, ty, tz, 1f,
    )

    @Test
    fun `first frame is emitted unchanged`() {
        val s = StandalonePoseStabilizer()
        val out = s.stabilize(view(0.3f))
        assertEquals(0.3f, out[12], eps)
    }

    @Test
    fun `a small move is damped toward the previous pose`() {
        val s = StandalonePoseStabilizer(alpha = 0.5f)
        s.stabilize(view(0f))                 // baseline
        val out = s.stabilize(view(0.05f))    // 5 cm < 0.20 m threshold → blend, not snap
        assertEquals("halfway between 0 and 0.05", 0.025f, out[12], eps)
    }

    @Test
    fun `a large move snaps through without lag`() {
        val s = StandalonePoseStabilizer(alpha = 0.5f)
        s.stabilize(view(0f))
        val out = s.stabilize(view(0.5f))     // 50 cm > 0.20 m threshold → pass through unsmoothed
        assertEquals(0.5f, out[12], eps)
    }

    @Test
    fun `reset makes the next frame snap instead of blending`() {
        val s = StandalonePoseStabilizer(alpha = 0.5f)
        s.stabilize(view(0f))
        s.reset()
        val out = s.stabilize(view(0.05f))    // would have blended to 0.025 without the reset
        assertEquals(0.05f, out[12], eps)
    }

    @Test
    fun `a steady pose converges and does not drift`() {
        val s = StandalonePoseStabilizer(alpha = 0.5f)
        var out = s.stabilize(view(0f))
        repeat(20) { out = s.stabilize(view(0.1f)) }
        assertEquals("EMA of a constant target reaches it", 0.1f, out[12], 1e-3f)
    }
}
