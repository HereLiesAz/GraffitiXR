package com.hereliesaz.graffitixr.feature.ar

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * [standaloneTapInFrame]: a standalone target-capture tap is a fraction of the FILL_CENTER screen,
 * but the photosphere view it is placed through describes the full analysis frame.
 */
class StandaloneTapInFrameTest {

    @Test
    fun `a tap is carried through the preview's centre crop into the full frame`() {
        // A 480x640 frame covering a 1080x2400 view shows frame columns 96..384 (scale 3.75).
        assertArrayEquals(
            floatArrayOf(96f / 480f, 0.25f),
            standaloneTapInFrame(0f to 0.25f, 1080 to 2400, 480, 640),
            1e-6f,
        )
        assertArrayEquals(
            floatArrayOf(0.5f + 0.25f * 288f / 480f, 0.5f),
            standaloneTapInFrame(0.75f to 0.5f, 1080 to 2400, 480, 640),
            1e-6f,
        )
    }

    @Test
    fun `without a view size the tap is taken as frame-relative`() {
        assertArrayEquals(floatArrayOf(0.2f, 0.7f), standaloneTapInFrame(0.2f to 0.7f, null, 480, 640), 0f)
    }

    @Test
    fun `no tap places at the frame centre`() {
        assertArrayEquals(floatArrayOf(0.5f, 0.5f), standaloneTapInFrame(null, 1080 to 2400, 480, 640), 0f)
    }
}
