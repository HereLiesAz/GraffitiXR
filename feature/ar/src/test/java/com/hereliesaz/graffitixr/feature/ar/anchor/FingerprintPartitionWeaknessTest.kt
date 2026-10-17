package com.hereliesaz.graffitixr.feature.ar.anchor

import com.hereliesaz.graffitixr.common.model.Fingerprint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.KeyPoint

/**
 * The tapped reference marks and the design placed over them.
 *
 * The flow tells the artist to tap their painted reference marks, then to place the design over
 * them. Those marks used to be partitioned INSIDE like any other feature under the design, and
 * relocalization only matches OUTSIDE marks, so the marks the artist chose could not be used. The
 * tapped marks are recorded as [Fingerprint.pinned] and the partition forces them OUTSIDE wherever
 * the design falls.
 */
class FingerprintPartitionWeaknessTest {

    private val identity = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
    private val outside = Footprint.Region.OUTSIDE.ordinal.toByte()
    private val inside = Footprint.Region.INSIDE.ordinal.toByte()

    /** 200 marks within 0.3 m of the centre of a 2 m x 1.5 m design placed over them. */
    private fun marks(): FloatArray {
        val pts = FloatArray(200 * 3)
        for (i in 0 until 200) {
            pts[i * 3] = ((i % 20) / 19f - 0.5f) * 0.6f
            pts[i * 3 + 1] = ((i / 20) / 9f - 0.5f) * 0.6f
            pts[i * 3 + 2] = 0f
        }
        return pts
    }

    @Test fun `unpinned marks under the design are inside, so a partition alone leaves no backbone`() {
        val regions = FingerprintPartition.classify(marks(), identity, halfW = 1.0f, halfH = 0.75f)
        assertEquals(0, FingerprintPartition.backboneCount(regions))
    }

    @Test fun `tapped marks stay outside under the design and form the backbone`() {
        val pinned = ByteArray(200) { 1 }
        val regions = FingerprintPartition.classify(marks(), identity, halfW = 1.0f, halfH = 0.75f, pinned = pinned)
        assertEquals(200, FingerprintPartition.backboneCount(regions))
        assertTrue(FingerprintPartition.backboneCount(regions) >= FingerprintPartition.MIN_BACKBONE)
    }

    @Test fun `only the pinned marks are forced outside, the rest are classified by where the design falls`() {
        val pinned = ByteArray(200) { if (it < 50) 1 else 0 }
        val regions = FingerprintPartition.classify(marks(), identity, halfW = 1.0f, halfH = 0.75f, pinned = pinned)
        for (i in 0 until 50) assertEquals(outside, regions[i])
        for (i in 50 until 200) assertEquals(inside, regions[i])
    }

    @Test fun `a pinned array that is not 1 to 1 with the points is ignored`() {
        val regions = FingerprintPartition.classify(marks(), identity, halfW = 1.0f, halfH = 0.75f, pinned = ByteArray(3) { 1 })
        assertEquals(0, FingerprintPartition.backboneCount(regions))
    }

    private fun fingerprint(rows: Int, pinned: ByteArray): Fingerprint = Fingerprint(
        keypoints = List(rows) { KeyPoint(1f, 1f, 7f) },
        points3d = List(rows * 3) { 0.01f * it },
        descriptorsData = ByteArray(rows * 32),
        descriptorsRows = rows,
        descriptorsCols = 32,
        descriptorsType = 0,
        captureAnchorCam = identity.toList(),
        pinned = pinned,
    )

    @Test fun `partition honours a fingerprint's pinned marks`() {
        val fp = fingerprint(4, byteArrayOf(1, 0, 1, 0))
        val design = FingerprintPartition.DesignFootprint(identity, 5f, 5f)   // covers every point
        val out = FingerprintPartition.partition(fp, design)
        assertArrayEquals(byteArrayOf(outside, inside, outside, inside), out.regions)
        assertEquals("pinned survives the partition", fp.pinned.toList(), out.pinned.toList())
    }

    @Test fun `pinned takes part in equality and is validated against the points`() {
        assertEquals(fingerprint(2, byteArrayOf(1, 0)), fingerprint(2, byteArrayOf(1, 0)))
        assertFalse(fingerprint(2, byteArrayOf(1, 0)) == fingerprint(2, byteArrayOf(0, 1)))
        assertThrows(IllegalArgumentException::class.java) { fingerprint(2, byteArrayOf(1)) }
    }
}
