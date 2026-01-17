package com.hereliesaz.graffitixr.utils

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.hereliesaz.graffitixr.UiState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var projectManager: ProjectManager
    private lateinit var tempFilesDir: File
    private lateinit var context: Context

    @Before
    fun setup() {
        mockkStatic(Uri::class)
        mockkStatic(Bitmap::class)

        tempFilesDir = tempFolder.newFolder("files")
        context = mockk()
        every { context.filesDir } returns tempFilesDir

        projectManager = ProjectManager()
    }

    @After
    fun tearDown() {
        unmockkStatic(Uri::class)
        unmockkStatic(Bitmap::class)
    }

    @Test
    fun `saveProject creates file and directory structure`() = runTest {
        // Mock Uri.fromFile safely
        every { Uri.fromFile(any()) } returns mockk<Uri>()

        // Mock Bitmap
        val mockBitmap = mockk<Bitmap>()
        every { mockBitmap.compress(any(), any(), any()) } returns true
        every { mockBitmap.width } returns 100
        every { mockBitmap.height } returns 100

        val uiState = UiState(
            capturedTargetImages = listOf(mockBitmap)
        )
        val projectId = "test_project"

        projectManager.saveProject(context, uiState, projectId)

        val projectDir = File(tempFilesDir, "projects/$projectId")
        assertTrue("Project directory should exist", projectDir.exists())

        val projectFile = File(projectDir, "project.json")
        assertTrue("project.json should exist", projectFile.exists())

        val targetFile = File(projectDir, "target_0.png")
        assertTrue("Target image file should exist", targetFile.exists())
    }
}
