package com.hereliesaz.graffitixr.feature.ar.anchor

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class HybridPageFrameTest {
    @Test
    fun `world page conversion round-trips camera composition`() {
        val sensorView = translated(0f, 0f, 1.5f)
        val cameraFromPage = translated(0.25f, -0.1f, -2f)

        val worldFromPage = HybridPageFrame.worldFromPage(sensorView, cameraFromPage)
        val recomposed = PoseMath.multiply(sensorView, worldFromPage)

        assertArrayEquals(cameraFromPage, recomposed, 1e-5f)
    }

    @Test
    fun `page from artwork survives global ARCore world rebase`() {
        val worldFromPage = translated(1f, 2f, -3f)
        val worldFromArtwork = translated(1.4f, 2.2f, -3f)
        val expected = HybridPageFrame.pageFromArtwork(worldFromPage, worldFromArtwork)

        val rebase = translated(7f, -4f, 2f)
        val rebasedPage = PoseMath.multiply(rebase, worldFromPage)
        val rebasedArtwork = PoseMath.multiply(rebase, worldFromArtwork)
        val actual = HybridPageFrame.pageFromArtwork(rebasedPage, rebasedArtwork)

        assertArrayEquals(expected, actual, 1e-5f)
    }

    private fun translated(x: Float, y: Float, z: Float) = floatArrayOf(
        1f,0f,0f,0f,
        0f,1f,0f,0f,
        0f,0f,1f,0f,
        x,y,z,1f,
    )
}
