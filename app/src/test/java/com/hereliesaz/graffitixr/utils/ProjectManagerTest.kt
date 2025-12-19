package com.hereliesaz.graffitixr.utils

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import com.hereliesaz.graffitixr.data.ProjectData
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

class ProjectManagerTest {

    private lateinit var projectManager: ProjectManager
    private val context: Context = mockk(relaxed = true)
    private lateinit var tempFilesDir: File
    private lateinit var tempProjectsDir: File

    @Before
    fun setup() {
        // Create a real temp directory for file operations
        tempFilesDir = createTempDir("files")
        tempProjectsDir = File(tempFilesDir, "projects")
        if (!tempProjectsDir.exists()) tempProjectsDir.mkdirs()

        every { context.filesDir } returns tempFilesDir

        projectManager = ProjectManager(context)
    }

    private fun createDummyProjectData(): ProjectData {
        return ProjectData(
            backgroundImageUri = null,
            overlayImageUri = null,
            opacity = 1f,
            contrast = 1f,
            saturation = 1f,
            colorBalanceR = 1f,
            colorBalanceG = 1f,
            colorBalanceB = 1f,
            scale = 1f,
            rotationZ = 0f,
            rotationX = 0f,
            rotationY = 0f,
            offset = Offset.Zero,
            blendMode = BlendMode.SrcOver,
            fingerprint = null,
            drawingPaths = emptyList()
        )
    }

    @Test
    fun `saveProject with path traversal throws SecurityException`() {
        val maliciousName = "../evil_project"
        val projectData = createDummyProjectData()

        try {
            projectManager.saveProject(maliciousName, projectData)
            fail("Expected SecurityException was not thrown")
        } catch (e: SecurityException) {
            // Expected
            val evilFile = File(tempFilesDir, "evil_project.json")
            assertFalse("File should NOT be written outside projects directory", evilFile.exists())
        }
    }

    @Test
    fun `saveProject with valid name writes to projects directory`() {
        val validName = "good_project"
        val projectData = createDummyProjectData()

        projectManager.saveProject(validName, projectData)

        val expectedFile = File(tempProjectsDir, "good_project.json")
        assertTrue("File should be written to projects directory", expectedFile.exists())
    }

    private fun createTempDir(prefix: String): File {
        val tempDir = File.createTempFile(prefix, "")
        tempDir.delete()
        tempDir.mkdir()
        tempDir.deleteOnExit()
        return tempDir
    }
}
