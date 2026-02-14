package com.hereliesaz.graffitixr.data.repository

import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.common.model.OverlayLayer
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
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
        val mockUri = mockk<android.net.Uri>()
        every { android.net.Uri.parse(any()) } returns mockUri

        val project = GraffitiProject(
            id = "test-id",
            name = "Test Project",
            layers = listOf(
                OverlayLayer(
                    id = "layer-1",
                    uri = android.net.Uri.parse("file://test"),
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
    }
}
