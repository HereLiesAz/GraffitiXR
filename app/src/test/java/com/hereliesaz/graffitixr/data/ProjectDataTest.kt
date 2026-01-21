package com.hereliesaz.graffitixr.data

import android.net.Uri
import io.mockk.mockkStatic
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.Before

class ProjectDataTest {

    @Before
    fun setup() {
        mockkStatic(Uri::class)
    }

    @Test
    fun testSerializationWithEmptyRefinementPaths() {
        val projectData = ProjectData(
            refinementPaths = emptyList()
        )

        val json = Json {
            encodeDefaults = true
        }

        // This should pass without exception
        val jsonString = json.encodeToString(projectData)

        // Basic verification
        assert(jsonString.contains("refinementPaths"))
        assert(jsonString.contains("[]"))
    }
}
