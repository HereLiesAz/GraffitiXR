package com.hereliesaz.graffitixr.data.repository

import android.content.Context
import android.net.Uri
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.data.ProjectManager
import com.hereliesaz.graffitixr.data.UriProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectRepositoryImplTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var uriProvider: UriProvider
    private lateinit var repository: ProjectRepositoryImpl
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = mockk()
        uriProvider = mockk()
        every { context.filesDir } returns tempFolder.root
        every { uriProvider.getUriForFile(any()) } returns mockk<Uri>()
        
        val projectManager = ProjectManager(context, uriProvider)
        repository = ProjectRepositoryImpl(context, projectManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `createProject creates a new project and saves it`() = runTest {
        val projectName = "Test Project"
        val project = repository.createProject(projectName)

        assertEquals(projectName, project.name)
        assertNotNull(project.id)

        val savedProject = repository.getProject(project.id)
        assertNotNull(savedProject)
        assertEquals(project.id, savedProject?.id)
        assertEquals(projectName, savedProject?.name)
    }

    @Test
    fun `deleteProject removes the project file`() = runTest {
        val project = repository.createProject("Delete Me")
        assertNotNull(repository.getProject(project.id))

        repository.deleteProject(project.id)
        assertNull(repository.getProject(project.id))
    }

    @Test
    fun `updateProject updates existing project`() = runTest {
        val project = repository.createProject("Original Name")
        val updatedProject = project.copy(name = "Updated Name")
        
        repository.updateProject(updatedProject)
        
        val retrievedProject = repository.getProject(project.id)
        assertEquals("Updated Name", retrievedProject?.name)
    }

    @Test
    fun `saveArtifact saves data to correct file`() = runTest {
        val project = repository.createProject("Artifact Project")
        val data = "test data".toByteArray()
        val filename = "test.bin"
        
        val path = repository.saveArtifact(project.id, filename, data)
        
        val file = File(path)
        assertTrue(file.exists())
        assertEquals("test data", file.readText())
    }

    @Test
    fun `updateTargetFingerprint updates path in metadata`() = runTest {
        val project = repository.createProject("Fingerprint Project")
        val path = "/path/to/fingerprint"
        
        // We need to set it as current first for some updateProject variations, 
        // but getProject/updateProject works on ID.
        repository.updateTargetFingerprint(project.id, path)
        
        val updated = repository.getProject(project.id)
        assertEquals(path, updated?.targetFingerprintPath)
    }
}
