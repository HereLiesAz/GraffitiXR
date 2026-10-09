package com.hereliesaz.graffitixr.feature.ar.anchor

import com.hereliesaz.sphereslam.SphereSlamPoseMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun centerPhotosphereFingerprint_hasExplicitNonIdentityMapTransform() {
        val m = StandaloneFingerprintFrame.mapFromFingerprint(
            azimuthDeltaDeg = 0f,
            elevationDeg = 0f,
            rangeUnits = 1f,
        )

        // Centre view points down -Z; the target plane is one normalized map unit in front.
        assertEquals(0f, m[12], 1e-6f)
        assertEquals(0f, m[13], 1e-6f)
        assertEquals(-1f, m[14], 1e-6f)
        assertFalse(StandaloneFingerprintFrame.isCenteredPageAnchor(m))

        // Local +X remains map +X, +Y remains map +Y, +Z faces back toward the camera.
        assertEquals(1f, m[0], 1e-6f)
        assertEquals(1f, m[5], 1e-6f)
        assertEquals(1f, m[10], 1e-6f)
    }

    @Test
    fun cameraFromMap_removesFingerprintModelTransform() {
        val mapFromFingerprint = StandaloneFingerprintFrame.mapFromFingerprint(
            azimuthDeltaDeg = 25f,
            elevationDeg = -10f,
            rangeUnits = 2.5f,
        )

        // Camera at the map origin: camera_from_fingerprint is exactly map_from_fingerprint.
        val cameraFromMap = StandaloneFingerprintFrame.cameraFromMap(
            cameraFromFingerprint = mapFromFingerprint,
            mapFromFingerprint = mapFromFingerprint,
        )

        assertArrayEquals(
            floatArrayOf(
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f,
            ),
            cameraFromMap,
            1e-5f,
        )
    }

    @Test
    fun photosphereFingerprintTransform_isRightHandedAtObliqueDirection() {
        val m = StandaloneFingerprintFrame.mapFromFingerprint(
            azimuthDeltaDeg = 30f,
            elevationDeg = 15f,
            rangeUnits = 2f,
        )
        val x = floatArrayOf(m[0], m[1], m[2])
        val y = floatArrayOf(m[4], m[5], m[6])
        val z = floatArrayOf(m[8], m[9], m[10])
        val cross = floatArrayOf(
            x[1] * y[2] - x[2] * y[1],
            x[2] * y[0] - x[0] * y[2],
            x[0] * y[1] - x[1] * y[0],
        )
        assertArrayEquals(z, cross, 1e-5f)

        val radius = kotlin.math.sqrt(m[12] * m[12] + m[13] * m[13] + m[14] * m[14])
        assertEquals(2f, radius, 1e-5f)
    }
}
