package com.hereliesaz.graffitixr.feature.dashboard

import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    private lateinit var viewModel: DashboardViewModel
    private lateinit var repository: ProjectRepository
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        viewModel = DashboardViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadAvailableProjects updates state with projects`() = runTest {
        val projects = listOf(
            GraffitiProject(id = "1", name = "P1"),
            GraffitiProject(id = "2", name = "P2")
        )
        coEvery { repository.getProjects() } returns projects

        viewModel.loadAvailableProjects()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(projects, viewModel.uiState.value.availableProjects)
        assertEquals(false, viewModel.uiState.value.isLoading)
    }

    @Test
    fun `openProject calls repository loadProject`() = runTest {
        val project = GraffitiProject(id = "1", name = "P1")
        
        viewModel.openProject(project)
        testDispatcher.scheduler.advanceUntilIdle()
        
        coVerify { repository.loadProject("1") }
    }

    @Test
    fun `onNewProject creates and updates project`() = runTest {
        val newProject = GraffitiProject(id = "new", name = "New Project")
        coEvery { repository.createProject(any<String>()) } returns newProject

        viewModel.onNewProject(isRightHanded = false)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { repository.createProject("New Project") }
        coVerify { repository.updateProject(match<GraffitiProject> { it.id == "new" && !it.isRightHanded }) }
    }

    @Test
    fun `deleteProject calls repository deleteProject and reloads`() = runTest {
        val projectId = "1"
        
        viewModel.deleteProject(projectId)
        testDispatcher.scheduler.advanceUntilIdle()
        
        coVerify { repository.deleteProject(projectId) }
        coVerify { repository.getProjects() } // loadAvailableProjects is called
    }
}
