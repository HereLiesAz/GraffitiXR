package com.hereliesaz.graffitixr.common.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.KeyPoint

/**
 * `IMPLEMENTATION.md` **2.9** — the Phase-2 partition survives a save/load, and a project written
 * before it existed still loads.
 *
 * The partition is only useful if it comes back off disk attached to the *same* points it was
 * computed against. A `regions` array that silently failed to serialize would not throw: the
 * fingerprint would deserialize as unpartitioned, which every consumer reads as all-backbone, so
 * the app would keep working and would quietly have Phase 2 switched off. That is the failure this
 * file exists to catch, and it is why the first thing asserted below is that the round trip
 * *distinguishes* a partitioned fingerprint from an unpartitioned one at all.
 */
class FingerprintPartitionPersistenceTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private companion object {
        const val INSIDE: Byte = 0
        const val BAND: Byte = 1
        const val OUTSIDE: Byte = 2
    }

    private fun fingerprint(
        regions: ByteArray = ByteArray(0),
        halfW: Float = -1f,
        halfH: Float = -1f,
        designModel: List<Float> = emptyList(),
    ) = Fingerprint(
        keypoints = List(3) { KeyPoint(it.toFloat(), 2f, 7f) },
        points3d = listOf(0f, 0f, 0f, 1f, 0f, 0f, 2f, 0f, 0f),
        descriptorsData = ByteArray(3 * 32) { (it % 251).toByte() },
        descriptorsRows = 3,
        descriptorsCols = 32,
        descriptorsType = 0,
        captureRotationDeg = 90,
        regions = regions,
        captureHalfW = halfW,
        captureHalfH = halfH,
        captureDesignModel = designModel,
    )

    private val identity16 = listOf(
        1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f,
    )

    /**
     * Equality has to notice the partition, or every other assertion in this file is decorative:
     * `assertEquals(original, decoded)` proves nothing about a field `equals` ignores.
     */
    @Test
    fun `two fingerprints differing only in their partition are not equal`() {
        val partitioned = fingerprint(byteArrayOf(INSIDE, BAND, OUTSIDE), 1f, 0.5f, identity16)
        assertNotEquals(fingerprint(), partitioned)
        assertNotEquals(
            "the extents are part of what the regions mean",
            partitioned, partitioned.copy(captureHalfW = 2f),
        )
        assertNotEquals(
            "the design model is what a later reclassify replays",
            partitioned, partitioned.copy(captureDesignModel = emptyList()),
        )
    }

    @Test
    fun `a partitioned fingerprint survives a round trip intact`() {
        val original = fingerprint(byteArrayOf(INSIDE, BAND, OUTSIDE), 1.25f, 0.75f, identity16)
        val decoded = json.decodeFromString<Fingerprint>(json.encodeToString(original))

        assertEquals(original, decoded)
        assertArrayEquals(byteArrayOf(INSIDE, BAND, OUTSIDE), decoded.regions)
        assertEquals(1.25f, decoded.captureHalfW, 0f)
        assertEquals(0.75f, decoded.captureHalfH, 0f)
        assertEquals(identity16, decoded.captureDesignModel)
        assertFalse(decoded.isUnpartitioned())
    }

    /**
     * The legacy path, and the reading that must never invert. A pre-Phase-2 project has no
     * `regions` key at all; it must decode to empty — "all backbone" — not fail and not default to
     * something that would exclude the whole map from the reloc PnP.
     */
    @Test
    fun `a payload written before Phase 2 decodes as unpartitioned`() {
        val legacy = """
            {
              "keypoints": [{"x":1.0,"y":2.0,"size":7.0,"angle":-1.0,"response":0.0,"octave":0,"classId":-1}],
              "points3d": [0.0, 0.0, 0.0],
              "descriptorsData": [],
              "descriptorsRows": 0,
              "descriptorsCols": 0,
              "descriptorsType": 0
            }
        """.trimIndent()
        val decoded = json.decodeFromString<Fingerprint>(legacy)

        assertTrue("no regions key must mean no partition", decoded.isUnpartitioned())
        assertEquals(0, decoded.regions.size)
        assertEquals(-1f, decoded.captureHalfW, 0f)
        assertEquals(-1f, decoded.captureHalfH, 0f)
        assertTrue(decoded.captureDesignModel.isEmpty())
        assertFalse("nothing to be stale against", decoded.isPartitionStale(1f, 1f))
    }

    /**
     * A truncated or hand-edited file must not construct a fingerprint whose partition indexes past
     * its points. Native subscripts `mWallRegions` by point index while building reloc
     * correspondences, so this is an out-of-bounds read, not a cosmetic inconsistency.
     */
    @Test(expected = IllegalArgumentException::class)
    fun `a regions array that disagrees with the point count is refused at construction`() {
        fingerprint(byteArrayOf(INSIDE))
    }

    /** Same argument for the stored matrix: 16 floats or nothing, never a short prefix. */
    @Test(expected = IllegalArgumentException::class)
    fun `a design model that is not a 4x4 is refused at construction`() {
        fingerprint(designModel = listOf(1f, 0f, 0f))
    }

    /**
     * `GraffitiProject` is what actually reaches disk, so the round trip is asserted through it too
     * rather than only through the nested type. A field that serializes standalone can still be
     * dropped by the enclosing document — for instance if the project ever stopped carrying the
     * whole `Fingerprint` and started projecting selected columns out of it.
     */
    @Test
    fun `the partition survives a round trip through the enclosing project`() {
        val fp = fingerprint(byteArrayOf(OUTSIDE, OUTSIDE, INSIDE), 2f, 1f, identity16)
        val project = GraffitiProject(id = "p1", name = "wall", fingerprint = fp)
        val decoded = json.decodeFromString<GraffitiProject>(json.encodeToString(project))

        assertArrayEquals(byteArrayOf(OUTSIDE, OUTSIDE, INSIDE), decoded.fingerprint!!.regions)
        assertEquals(2f, decoded.fingerprint!!.captureHalfW, 0f)
        assertEquals(identity16, decoded.fingerprint!!.captureDesignModel)
    }
}
