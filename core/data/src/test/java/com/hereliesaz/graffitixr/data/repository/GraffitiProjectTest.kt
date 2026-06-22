package com.hereliesaz.graffitixr.data.repository

import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.common.model.OverlayLayer
import com.hereliesaz.graffitixr.common.model.WallFeatureMap
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class GraffitiProjectTest {

    private val json = Json { 
        prettyPrint = true 
        ignoreUnknownKeys = true 
        encodeDefaults = true
    }

    @Before
    fun setUp() {
        mockkStatic(android.net.Uri::class)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `serialization preserves all fields`() {
        // Use a mock URI that returns a valid string representation
        val mockUri = mockk<android.net.Uri>()
        every { mockUri.toString() } returns "file://test"
        every { android.net.Uri.parse("file://test") } returns mockUri

        val project = GraffitiProject(
            id = "test-id",
            name = "Test Project",
            layers = listOf(
                OverlayLayer(
                    id = "layer-1",
                    uri = mockUri,
                    warpMesh = listOf(0f, 1f, 2f, 3f),
                    isImageLocked = true
                )
            ),
            targetFingerprintPath = "/path/to/fingerprint"
        )

        assertEquals("test-id", project.id)
        assertEquals("Test Project", project.name)
        assertEquals(true, project.layers[0].isImageLocked)
        assertEquals(listOf(0f, 1f, 2f, 3f), project.layers[0].warpMesh)

        // Actually exercise serialization — the test name promised a round-trip but only ever
        // checked the constructed object's fields, so a serialization regression went uncaught.
        val decoded = json.decodeFromString<GraffitiProject>(json.encodeToString(project))
        assertEquals("test-id", decoded.id)
        assertEquals("Test Project", decoded.name)
        assertEquals(true, decoded.layers[0].isImageLocked)
        assertEquals(listOf(0f, 1f, 2f, 3f), decoded.layers[0].warpMesh)
        assertEquals("/path/to/fingerprint", decoded.targetFingerprintPath)
    }

    @Test
    fun `serialization preserves the wall feature map`() {
        // The persistent feature map rides in project.json (and thus the .gxr), parallel to the
        // fingerprint. Verify it survives the project round-trip intact (descriptor blob included).
        val map = WallFeatureMap(
            points3d = floatArrayOf(0f, 0f, 1f, 0.1f, 0.2f, 1.1f),
            descriptorsData = ByteArray(64) { (it % 251).toByte() },
            descriptorsRows = 2,
            descriptorsCols = 32,
            descriptorsType = 0,
            confidence = floatArrayOf(0.9f, 0.4f),
            obsCount = intArrayOf(4, 1),
            anchor = FloatArray(16) { it.toFloat() },
            intrinsics = floatArrayOf(500f, 500f, 320f, 240f),
        )
        val project = GraffitiProject(id = "id", name = "n", wallFeatureMap = map)

        val decoded = json.decodeFromString<GraffitiProject>(json.encodeToString(project))

        assertEquals(map, decoded.wallFeatureMap)
    }

    @Test
    fun `serialization preserves per-host rail expansion`() {
        val expansion = mapOf("host.design" to true, "design.layers" to false)
        val project = GraffitiProject(id = "id", name = "n", railExpansion = expansion)

        val decoded = json.decodeFromString<GraffitiProject>(json.encodeToString(project))

        assertEquals(expansion, decoded.railExpansion)
    }
}
