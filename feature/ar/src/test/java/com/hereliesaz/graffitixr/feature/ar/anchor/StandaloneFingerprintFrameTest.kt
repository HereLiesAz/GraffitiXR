package com.hereliesaz.graffitixr.feature.ar.anchor

import com.hereliesaz.sphereslam.SphereSlamPoseMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StandaloneFingerprintFrameTest {

    @Test
    fun referencePixelToWall_matchesArtoolkitxKpmHalfPixelCoordinates() {
        val width = 1000
        val height = 500
        val dpi = 100f
        val geometry = SphereSlamPoseMath.pageGeometry(width, height, dpi)
        val u = 123.25f
        val v = 87.75f

        val fromPixel = StandaloneFingerprintFrame.referencePixelToWall(
            u, v, width, height, geometry,
        )

        val xMm = (u + 0.5f) / dpi * 25.4f
        val yMm = ((height - 0.5f) - v) / dpi * 25.4f
        val fromKpm = StandaloneFingerprintFrame.kpmPageMillimetersToWall(
            xMm, yMm, geometry,
        )

        assertEquals(fromKpm.x, fromPixel.x, 1e-6f)
        assertEquals(fromKpm.y, fromPixel.y, 1e-6f)
        assertEquals(0f, fromPixel.z, 0f)
    }

    @Test
    fun oddSizedReference_centerPixel_isWallOrigin() {
        val width = 5
        val height = 3
        val dpi = SphereSlamPoseMath.dpiForReferenceWidth(width, 1f)
        val geometry = SphereSlamPoseMath.pageGeometry(width, height, dpi)

        val center = StandaloneFingerprintFrame.referencePixelToWall(
            u = 2f,
            v = 1f,
            widthPixels = width,
            heightPixels = height,
            geometry = geometry,
        )

        assertEquals(0f, center.x, 1e-6f)
        assertEquals(0f, center.y, 1e-6f)
        assertEquals(0f, center.z, 0f)
    }

    @Test
    fun referenceY_isWallUp_notImageDown() {
        val width = 100
        val height = 100
        val dpi = SphereSlamPoseMath.dpiForReferenceWidth(width, 1f)
        val geometry = SphereSlamPoseMath.pageGeometry(width, height, dpi)

        val top = StandaloneFingerprintFrame.referencePixelToWall(
            49.5f, 0f, width, height, geometry,
        )
        val bottom = StandaloneFingerprintFrame.referencePixelToWall(
            49.5f, 99f, width, height, geometry,
        )

        assertTrue(top.y > 0f)
        assertTrue(bottom.y < 0f)
        assertEquals(-bottom.y, top.y, 1e-6f)
    }

    @Test
    fun mobileGsObjectFrame_isExactlyRendererWallFrame() {
        val wall = StandaloneFingerprintFrame.Point3(0.25f, -0.4f, 0f)
        assertArrayEquals(
            floatArrayOf(0.25f, -0.4f, 0f),
            StandaloneFingerprintFrame.wallToMobileGsObject(wall),
            0f,
        )
    }

    @Test
    fun standaloneFingerprintAndAnchorFrames_areIdentityRelated() {
        val identity = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
        assertArrayEquals(identity, StandaloneFingerprintFrame.fingerprintFromAnchor(), 0f)
        assertArrayEquals(identity, StandaloneFingerprintFrame.anchorFromFingerprint(), 0f)
    }
}
