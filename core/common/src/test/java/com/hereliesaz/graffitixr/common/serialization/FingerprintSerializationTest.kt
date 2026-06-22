package com.hereliesaz.graffitixr.common.serialization

import com.hereliesaz.graffitixr.common.model.Fingerprint
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.opencv.core.KeyPoint

/**
 * Round-trip coverage for the relocalization fingerprint — the load-bearing payload of a saved
 * `.gxr` (descriptors + 3D points + distortion-head patch). This is the data the PnP relocalizer
 * restores on project open, so it must survive serialization byte-for-byte. The reloc *math*
 * (PoseFusion, MetricMarks/Triangulation) is already covered by their own suites; this fills the
 * remaining gap and guards the format against the upcoming `.gxr` reshape.
 */
class FingerprintSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `KeyPointSerializer round-trips every field`() {
        // Values chosen to be exactly float-representable so the double<->float hop is lossless.
        val kp = KeyPoint(12.5f, -3.25f, 7f, 45f, 0.75f, 2, 9)
        val decoded = json.decodeFromString(
            KeyPointSerializer,
            json.encodeToString(KeyPointSerializer, kp),
        )
        assertEquals(12.5, decoded.pt.x, 1e-4)
        assertEquals(-3.25, decoded.pt.y, 1e-4)
        assertEquals(7f, decoded.size, 1e-4f)
        assertEquals(45f, decoded.angle, 1e-4f)
        assertEquals(0.75f, decoded.response, 1e-4f)
        assertEquals(2, decoded.octave)
        assertEquals(9, decoded.class_id)
    }

    @Test
    fun `Fingerprint survives a full serialization round-trip`() {
        val original = Fingerprint(
            keypoints = listOf(
                KeyPoint(1f, 2f, 3f, 10f, 0.5f, 0, 1),
                KeyPoint(4.5f, 6.25f, 8f, 90f, 0.875f, 3, 7),
            ),
            points3d = listOf(0.5f, 0.25f, 0.75f, -1.5f, 2.0f, 3.5f),
            descriptorsData = byteArrayOf(1, 2, 3, 4, -5, -6, 127, -128),
            descriptorsRows = 2,
            descriptorsCols = 4,
            descriptorsType = 0,
            patchData = ByteArray(16) { (it * 7).toByte() },
        )

        val decoded = json.decodeFromString(
            Fingerprint.serializer(),
            json.encodeToString(Fingerprint.serializer(), original),
        )

        // Raw byte payloads — descriptors (for matching) and the distortion-head patch — must be intact.
        assertArrayEquals(original.descriptorsData, decoded.descriptorsData)
        assertArrayEquals(original.patchData, decoded.patchData)
        // Mat-reconstruction metadata + the metric 3D points used by PnP.
        assertEquals(original.descriptorsRows, decoded.descriptorsRows)
        assertEquals(original.descriptorsCols, decoded.descriptorsCols)
        assertEquals(original.descriptorsType, decoded.descriptorsType)
        assertEquals(original.points3d, decoded.points3d)
        // Keypoints compared field-wise: OpenCV's KeyPoint has no value-based equals().
        assertEquals(original.keypoints.size, decoded.keypoints.size)
        original.keypoints.forEachIndexed { i, kp ->
            val d = decoded.keypoints[i]
            assertEquals(kp.pt.x, d.pt.x, 1e-4)
            assertEquals(kp.pt.y, d.pt.y, 1e-4)
            assertEquals(kp.size, d.size, 1e-4f)
            assertEquals(kp.angle, d.angle, 1e-4f)
            assertEquals(kp.response, d.response, 1e-4f)
            assertEquals(kp.octave, d.octave)
            assertEquals(kp.class_id, d.class_id)
        }
    }

    @Test
    fun `legacy fingerprint without points3d or patch deserializes with defaults`() {
        // Pre-existing projects predate points3d/patchData. They must still load (defaulted), so the
        // format stays backward-compatible across the `.gxr` reshape. ByteArray encodes as a JSON
        // number array under kotlinx.serialization.
        val legacyJson =
            """{"keypoints":[],"descriptorsData":[1,2,3],"descriptorsRows":1,"descriptorsCols":3,"descriptorsType":0}"""
        val decoded = json.decodeFromString(Fingerprint.serializer(), legacyJson)

        assertEquals(0, decoded.points3d.size)
        assertEquals(0, decoded.patchData.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), decoded.descriptorsData)
        assertEquals(1, decoded.descriptorsRows)
    }
}
