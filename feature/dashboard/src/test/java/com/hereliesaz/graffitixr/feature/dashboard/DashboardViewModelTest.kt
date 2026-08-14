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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `onNewProjectTriggered shows the dialog`() = runTest {
        viewModel.onNewProjectTriggered()
        assertEquals(true, viewModel.uiState.value.showNewProjectDialog)
    }

    @Test
    fun `onCreateProject creates the project and dismisses the dialog`() = runTest {
        val newProject = GraffitiProject(id = "new", name = "Test Project")
        coEvery { repository.createProject(any<String>()) } returns newProject

        viewModel.onCreateProject(name = "Test Project")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { repository.createProject("Test Project") }
        assertEquals(false, viewModel.uiState.value.showNewProjectDialog)
    }

    @Test
    fun `deleteProject calls repository deleteProject and reloads`() = runTest {
        val projectId = "1"
        
        viewModel.deleteProject(projectId)
        testDispatcher.scheduler.advanceUntilIdle()
        
        coVerify { repository.deleteProject(projectId) }
        coVerify { repository.getProjects() } // loadAvailableProjects is called
    }

    // ── Update check: pure helpers ────────────────────────────────────────────

    @Test
    fun `isNewerVersion compares segments numerically`() {
        assertTrue(viewModel.isNewerVersion("1.2.4", "1.2.3"))
        assertTrue(viewModel.isNewerVersion("1.3.0", "1.2.9"))
        assertTrue(viewModel.isNewerVersion("2.0.0", "1.99.99"))
        assertFalse(viewModel.isNewerVersion("1.2.3", "1.2.3"))
        assertFalse(viewModel.isNewerVersion("1.2.3", "1.2.4"))
    }

    @Test
    fun `isNewerVersion does not collapse a qualified segment to zero`() {
        // "3-beta" used to parse as 0, so 1.2.3-beta read as 1.2.0 and a genuinely newer release
        // was reported as older — the update prompt never appeared.
        assertTrue(viewModel.isNewerVersion("1.2.3-beta", "1.2.2"))
        assertFalse(viewModel.isNewerVersion("1.2.3-beta", "1.2.4"))
    }

    @Test
    fun `isNewerVersion tolerates a v prefix and differing segment counts`() {
        assertTrue(viewModel.isNewerVersion("v1.3", "1.2.9"))
        assertFalse(viewModel.isNewerVersion("1.2", "1.2.0"))
    }

    @Test
    fun `parseRelease picks the release url, not the author's`() {
        // GitHub's payload carries the author object's html_url too. Matching the first html_url in
        // the document only worked because of field ordering; assert on a payload that puts the
        // author first.
        val json = """
            {"tag_name":"v2.1.0",
             "author":{"login":"someone","html_url":"https://github.com/someone"},
             "html_url":"https://github.com/hereliesaz/GraffitiXR/releases/tag/v2.1.0"}
        """.trimIndent()
        val release = viewModel.parseRelease(json)
        assertEquals("v2.1.0", release?.tagName)
        assertEquals("https://github.com/hereliesaz/GraffitiXR/releases/tag/v2.1.0", release?.htmlUrl)
    }

    @Test
    fun `parseRelease returns null without a tag name`() {
        assertNull(viewModel.parseRelease("""{"html_url":"https://github.com/x/y/releases/tag/v1"}"""))
    }
}
