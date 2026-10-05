package com.hereliesaz.graffitixr.common.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 1 of the spherical-coverage map (docs/SPHERESLAM_SPHERE_MAP.md): per-keyframe orientation
 * is storage-only, so its sole requirement right now is that it survives a serialize/deserialize
 * round-trip intact — including when carried on a [GraffitiProject] and when absent on a legacy
 * project.
 */
class KeyframeOrientationsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `round-trips a populated orientation log`() {
        val original = KeyframeOrientations(
            timestampsNs = longArrayOf(1_000L, 2_000L, 3_000L),
            quaternions = floatArrayOf(
                0f, 0f, 0f, 1f,
                0f, 0.7071f, 0f, 0.7071f,
                0.5f, 0.5f, 0.5f, 0.5f,
            ),
        )

        val decoded = json.decodeFromString<KeyframeOrientations>(json.encodeToString(original))

        assertEquals(original, decoded)
        assertEquals(3, decoded.count)
        assertTrue(original.quaternions.contentEquals(decoded.quaternions))
        assertTrue(original.timestampsNs.contentEquals(decoded.timestampsNs))
    }

    @Test
    fun `empty log round-trips (back-compat default)`() {
        val decoded = json.decodeFromString<KeyframeOrientations>(json.encodeToString(KeyframeOrientations()))
        assertEquals(KeyframeOrientations(), decoded)
        assertEquals(0, decoded.count)
    }

    @Test
    fun `constructor rejects a quaternion blob that is not four floats per keyframe`() {
        try {
            KeyframeOrientations(timestampsNs = longArrayOf(1L, 2L), quaternions = floatArrayOf(0f, 0f, 0f))
            fail("expected the 4-per-keyframe size check to reject a mismatched quaternion blob")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `project round-trips with an orientation log`() {
        val project = GraffitiProject(
            id = "p1",
            sphereSlamKeyframeOrientations = KeyframeOrientations(
                timestampsNs = longArrayOf(42L),
                quaternions = floatArrayOf(0f, 0f, 0f, 1f),
            ),
        )

        val decoded = json.decodeFromString<GraffitiProject>(json.encodeToString(project))

        assertEquals(project.sphereSlamKeyframeOrientations, decoded.sphereSlamKeyframeOrientations)
        assertEquals(1, decoded.sphereSlamKeyframeOrientations?.count)
    }

    @Test
    fun `legacy project without the field deserializes to null`() {
        // A project JSON written before this field existed has no key for it; the default must apply.
        val legacy = json.encodeToString(GraffitiProject(id = "legacy"))
        val decoded = json.decodeFromString<GraffitiProject>(legacy)
        assertNull(decoded.sphereSlamKeyframeOrientations)
    }
}
