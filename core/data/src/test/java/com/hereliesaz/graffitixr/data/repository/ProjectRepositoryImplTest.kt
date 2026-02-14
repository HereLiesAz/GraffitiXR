package com.hereliesaz.graffitixr.data.repository

import android.content.Context
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.data.ProjectManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    private lateinit var repository: ProjectRepositoryImpl
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = mockk()
        every { context.filesDir } returns tempFolder.root
        val projectManager = ProjectManager(context)
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
}
