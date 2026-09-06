package com.hereliesaz.graffitixr.feature.ar.rendering

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-math correctness for [letterboxViewport] — the fix for a bug the audit caught: the fallback
 * overlay drew into the full GL surface while `CameraPreview`'s `PreviewView` beneath it was
 * cropped to a different aspect (the CameraX default, `FILL_CENTER`), stretching and mis-scaling
 * the tracked design relative to what the artist actually sees. This checks the letterbox math
 * against hand-computed expectations for both the "frame wider than surface" and "frame taller
 * than surface" cases, plus the surface-exactly-matches-frame no-op case.
 */
class HomographyOverlayRendererTest {

    @Test
    fun `a frame wider than the surface is letterboxed top and bottom`() {
        // 16:9 frame (1.778) inside a 9:16 surface (0.5625) of 1080x1920: fit width, bar top/bottom.
        val vp = letterboxViewport(surfaceWidth = 1080, surfaceHeight = 1920, frameAspect = 16f / 9f)
        requireNotNull(vp)
        // width = 1080, height = 1080 / (16/9) = 607.5 -> 607 (Int truncation)
        assertArrayEquals(intArrayOf(0, (1920 - 607) / 2, 1080, 607), vp)
    }

    @Test
    fun `a frame narrower than the surface is letterboxed left and right`() {
        // A 9:16 phone surface is already fairly narrow (aspect 0.5625) — 3:4 (0.75) is WIDER than
        // that, not narrower (a common mistake: "portrait" doesn't mean "narrower than a phone
        // screen"). Use something actually narrower than 0.5625, e.g. 1:2 (0.5): fit height, bar
        // left/right.
        val vp = letterboxViewport(surfaceWidth = 1080, surfaceHeight = 1920, frameAspect = 1f / 2f)
        requireNotNull(vp)
        // height = 1920, width = 1920 * 0.5 = 960
        assertArrayEquals(intArrayOf((1080 - 960) / 2, 0, 960, 1920), vp)
    }

    @Test
    fun `a frame matching the surface's own aspect fills it with no bars`() {
        val vp = letterboxViewport(surfaceWidth = 1080, surfaceHeight = 1920, frameAspect = 1080f / 1920f)
        assertArrayEquals(intArrayOf(0, 0, 1080, 1920), vp)
    }

    @Test
    fun `an unset surface or aspect returns null rather than a garbage viewport`() {
        assertNull(letterboxViewport(0, 1920, 1f))
        assertNull(letterboxViewport(1080, 0, 1f))
        assertNull(letterboxViewport(1080, 1920, 0f))
        assertNull(letterboxViewport(1080, 1920, -1f))
    }
}
