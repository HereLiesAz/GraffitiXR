package com.hereliesaz.graffitixr.feature.ar.anchor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The repartition trigger, pinned. This logic shipped wrong twice, in two different ways, and both
 * times it was unreachable from a test because it lived inside a `GLSurfaceView` frame callback —
 * so the tests below are the reason it was extracted at all.
 *
 * The failure that matters most is the slow drag. A trigger that fires too often wastes work; one
 * that never fires leaves Φ classifying the fingerprint against a design that has physically moved
 * away, and the artist gets a target that will not lock with no indication why.
 */
class DesignMoveDetectorTest {

    private companion object {
        const val W = 1.0f
        const val H = 0.5f
    }

    private fun detector() = DesignMoveDetector()

    /** No previous publish exists, so there is nothing for the partition to already match. */
    @Test
    fun `the first call always reports movement`() {
        assertTrue(detector().moved(0f, 0f, 0f, W, H))
    }

    @Test
    fun `a repeat of the published values reports nothing`() {
        val d = detector()
        assertTrue(d.moved(0.2f, 0.3f, 15f, W, H))
        assertFalse(d.moved(0.2f, 0.3f, 15f, W, H))
        assertFalse("still nothing on a third identical frame", d.moved(0.2f, 0.3f, 15f, W, H))
    }

    /**
     * **The slow drag.** 15 cm over three seconds at 60 fps is 0.083 mm per frame — under the 1 mm
     * threshold on every single frame. If the detector advanced its remembered values on frames that
     * reported no movement, the delta would never accumulate and this would never fire, leaving the
     * partition 15 cm out. Fast drags fired and the careful final placement did not, which is the
     * worst possible split: nudging the design into position is the last thing an artist does.
     */
    @Test
    fun `a drag slower than the threshold per frame still eventually fires`() {
        val d = detector()
        assertTrue(d.moved(0f, 0f, 0f, W, H))

        val perFrame = 0.000083f // 15 cm / (3 s x 60 fps)
        var x = 0f
        var fired = false
        repeat(180) {
            x += perFrame
            if (d.moved(x, 0f, 0f, W, H)) fired = true
        }
        assertTrue("a 15 cm drag must repartition, however slowly it is made", fired)
        assertTrue("...and it must have travelled the full distance", x > 0.014f)
    }

    /** The same argument for a slow rotation: a 4° alignment tweak over two seconds. */
    @Test
    fun `a rotation slower than the threshold per frame still eventually fires`() {
        val d = detector()
        assertTrue(d.moved(0f, 0f, 0f, W, H))

        var deg = 0f
        var fired = false
        repeat(120) {
            deg += 4f / 120f // 0.033 deg/frame, under the 0.1 deg threshold
            if (d.moved(0f, 0f, deg, W, H)) fired = true
        }
        assertTrue("a 4 degree tweak must repartition", fired)
    }

    /**
     * The counterpart: genuine stillness must stay quiet. Float noise at a tenth of the threshold
     * must not fire, or a resting finger repartitions the fingerprint, replaces the native map and
     * rewrites the project file every frame.
     */
    @Test
    fun `noise below the threshold never fires, however many frames pass`() {
        val d = detector()
        assertTrue(d.moved(0.5f, 0.5f, 10f, W, H))

        var fired = false
        repeat(600) { i ->
            // Alternating jitter, so it cannot accumulate into a real move.
            val j = if (i % 2 == 0) 1e-4f else -1e-4f
            if (d.moved(0.5f + j, 0.5f + j, 10f + j, W, H)) fired = true
        }
        assertFalse("jitter must not be mistaken for a drag", fired)
    }

    /** Each axis on its own, so a detector that ignored one would not hide behind the others. */
    @Test
    fun `every tracked quantity fires independently`() {
        for ((name, call) in listOf<Pair<String, (DesignMoveDetector) -> Boolean>>(
            "panX" to { d -> d.moved(1f, 0f, 0f, W, H) },
            "panY" to { d -> d.moved(0f, 1f, 0f, W, H) },
            "rotation" to { d -> d.moved(0f, 0f, 30f, W, H) },
            "halfW" to { d -> d.moved(0f, 0f, 0f, W * 2, H) },
            "halfH" to { d -> d.moved(0f, 0f, 0f, W, H * 2) },
        )) {
            val d = detector()
            assertTrue(d.moved(0f, 0f, 0f, W, H))
            assertTrue("$name must be tracked", call(d))
        }
    }

    /** Teardown forgets the session's last publish, so the next one is treated as the first. */
    @Test
    fun `reset makes the next call report movement again`() {
        val d = detector()
        assertTrue(d.moved(0.2f, 0.3f, 15f, W, H))
        assertFalse(d.moved(0.2f, 0.3f, 15f, W, H))
        d.reset()
        assertTrue("after teardown there is no published footprint to match", d.moved(0.2f, 0.3f, 15f, W, H))
    }
}
