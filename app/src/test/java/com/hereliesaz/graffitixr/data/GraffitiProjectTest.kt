package com.hereliesaz.graffitixr.data

import android.net.Uri
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import io.mockk.mockkStatic
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.Before

class GraffitiProjectTest {

    @Before
    fun setup() {
        mockkStatic(Uri::class)
    }

    @Test
    fun testSerializationWithEmptyRefinementPaths() {
        val projectData = GraffitiProject(
            id = "test-id",
            name = "Test Project",
            refinementPaths = emptyList()
        )

        val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        // This should pass without exception
        val jsonString = json.encodeToString(projectData)

        // Basic verification
        assert(jsonString.contains("refinementPaths"))
        assert(jsonString.contains("[]"))
    }
}
