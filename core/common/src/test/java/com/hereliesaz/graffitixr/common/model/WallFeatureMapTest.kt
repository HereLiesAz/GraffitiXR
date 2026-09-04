package com.hereliesaz.graffitixr.common.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 1 of the persistent-feature-map work (docs/RELOC_MAP_DESIGN.md): the data contract
 * must survive a serialize/deserialize round-trip intact — especially the descriptor blob,
 * which a naive ByteArray equals() would compare by reference.
 */
class WallFeatureMapTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `round-trips a populated ORB feature map`() {
        val n = 3
        val cols = 32 // ORB descriptor width in bytes
        val original = WallFeatureMap(
            points3d = floatArrayOf(0f, 0f, 1f, 0.1f, 0.2f, 1.1f, -0.3f, 0.4f, 0.9f),
            descriptorsData = ByteArray(n * cols) { (it % 251).toByte() },
            descriptorsRows = n,
            descriptorsCols = cols,
            descriptorsType = 0, // CV_8U (ORB)
            confidence = floatArrayOf(0.9f, 0.5f, 0.75f),
            obsCount = intArrayOf(5, 2, 3),
            anchor = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f),
            intrinsics = floatArrayOf(500f, 500f, 320f, 240f),
        )

        val decoded = json.decodeFromString<WallFeatureMap>(json.encodeToString(original))

        assertEquals(original, decoded)
        assertEquals(n, decoded.pointCount)
        assertTrue(
            "descriptor blob must survive the round-trip byte-for-byte",
            original.descriptorsData.contentEquals(decoded.descriptorsData)
        )
    }

    @Test
    fun `round-trips a SuperPoint-typed map (CV_32F descriptor type preserved)`() {
        val original = WallFeatureMap(
            points3d = floatArrayOf(1f, 2f, 3f),
            descriptorsData = ByteArray(256 * 4) { (it % 97).toByte() }, // 1 point x 256 floats
            descriptorsRows = 1,
            descriptorsCols = 256,
            descriptorsType = 5, // CV_32F (SuperPoint) — must round-trip unchanged
            confidence = floatArrayOf(0.6f),
            obsCount = intArrayOf(1),
        )

        val decoded = json.decodeFromString<WallFeatureMap>(json.encodeToString(original))

        assertEquals(original, decoded)
        assertEquals(5, decoded.descriptorsType)
        assertEquals(256, decoded.descriptorsCols)
    }

    @Test
    fun `empty map round-trips (back-compat default)`() {
        val decoded = json.decodeFromString<WallFeatureMap>(json.encodeToString(WallFeatureMap()))
        assertEquals(WallFeatureMap(), decoded)
        assertEquals(0, decoded.pointCount)
    }

    /**
     * A crafted/corrupt `descriptorsRows` (e.g. from an imported `.gxr`) must not be able to slip a
     * tiny `points3d` array past the size check by overflowing `descriptorsRows * 3` in 32-bit Int.
     * 1_431_655_766 * 3 == 4_294_967_298, which wraps a 32-bit Int to 2 — so a hostile row count
     * paired with a 2-element points3d would satisfy a naive `Int` comparison even though the real
     * required size (over four billion floats) could never actually be allocated. The constructor
     * must reject this regardless (see the 64-bit-widened check in the init block).
     */
    @Test
    fun `constructor rejects a descriptorsRows count that would 32-bit-overflow the points3d size check`() {
        val hostileRows = 1_431_655_766
        val wrappedSize = 2 // (hostileRows * 3) truncated to a 32-bit Int wraps to this
        try {
            WallFeatureMap(points3d = FloatArray(wrappedSize), descriptorsRows = hostileRows)
            fail("expected the 64-bit-widened points3d size check to reject a wrapped hostile row count")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
