package com.hereliesaz.graffitixr.feature.ar.anchor

import com.hereliesaz.sphereslam.SphereSlamPoseMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.opencv.core.CvType

class StandaloneFingerprintBuilderTest {

    @Test
    fun assemble_placesEveryDescriptorOnCenteredKpmPagePlane() {
        val width = 101
        val height = 51
        val dpi = SphereSlamPoseMath.dpiForReferenceWidth(width, 2f)
        val geometry = SphereSlamPoseMath.pageGeometry(width, height, dpi)
        val rows = 8
        val positions = floatArrayOf(
            50f, 25f,
            10f, 10f,
            90f, 10f,
            10f, 40f,
            90f, 40f,
            25f, 25f,
            75f, 25f,
            50f, 40f,
        )
        val descriptors = ByteArray(rows * 4) { it.toByte() }

        val fp = StandaloneFingerprintBuilder.assemble(
            detected = StandaloneFingerprintBuilder.DetectedFeatures(
                positions = positions,
                descriptorsData = descriptors,
                rows = rows,
                cols = 4,
                type = CvType.CV_8U,
            ),
            widthPixels = width,
            heightPixels = height,
            geometry = geometry,
            minPoints = 8,
        )

        assertNotNull(fp)
        fp!!
        assertEquals(rows, fp.descriptorsRows)
        assertEquals(rows * 3, fp.points3d.size)
        assertArrayEquals(descriptors, fp.descriptorsData)

        for (i in 0 until rows) {
            val expected = StandaloneFingerprintFrame.referencePixelToWall(
                positions[i * 2],
                positions[i * 2 + 1],
                width,
                height,
                geometry,
            )
            assertEquals(expected.x, fp.points3d[i * 3], 1e-6f)
            assertEquals(expected.y, fp.points3d[i * 3 + 1], 1e-6f)
            assertEquals(0f, fp.points3d[i * 3 + 2], 0f)
        }
    }

    @Test
    fun assemble_filtersInvalidPositionsAndMatchingDescriptorRowsTogether() {
        val width = 100
        val height = 100
        val geometry = SphereSlamPoseMath.pageGeometry(width, height, 100f)
        val positions = floatArrayOf(
            10f, 10f,
            -4f, 30f,
            20f, 20f,
            Float.NaN, 40f,
            30f, 30f,
            40f, 40f,
            50f, 50f,
            60f, 60f,
            70f, 70f,
            80f, 80f,
        )
        val descriptors = ByteArray(10 * 2) { it.toByte() }

        val fp = StandaloneFingerprintBuilder.assemble(
            StandaloneFingerprintBuilder.DetectedFeatures(
                positions, descriptors, 10, 2, CvType.CV_8U,
            ),
            width,
            height,
            geometry,
            minPoints = 8,
        )

        assertNotNull(fp)
        fp!!
        assertEquals(8, fp.descriptorsRows)
        assertArrayEquals(
            byteArrayOf(
                0, 1,
                4, 5,
                8, 9,
                10, 11,
                12, 13,
                14, 15,
                16, 17,
                18, 19,
            ),
            fp.descriptorsData,
        )
    }

    @Test
    fun assemble_refusesTooFewValidRows() {
        val geometry = SphereSlamPoseMath.pageGeometry(10, 10, 100f)
        val fp = StandaloneFingerprintBuilder.assemble(
            StandaloneFingerprintBuilder.DetectedFeatures(
                positions = FloatArray(7 * 2) { 2f },
                descriptorsData = ByteArray(7 * 4),
                rows = 7,
                cols = 4,
                type = CvType.CV_8U,
            ),
            widthPixels = 10,
            heightPixels = 10,
            geometry = geometry,
            minPoints = 8,
        )
        assertNull(fp)
    }
}
