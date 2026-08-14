package com.hereliesaz.graffitixr.feature.dashboard

import android.net.Uri
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
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

        // The failure paths in openProject/importProject log via android.util.Log, which isn't
        // available in plain JVM unit tests (matches ProjectManagerTest's pattern).
        mockkStatic(android.util.Log::class)
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(android.util.Log::class)
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
        coEvery { repository.loadProject("1") } returns Result.success(Unit)

        viewModel.openProject(project)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { repository.loadProject("1") }
    }

    @Test
    fun `openProject success threads the real project name into state and fires navigation`() = runTest {
        // Regression test: the rename dialog used to pre-fill from a UUID because nothing tracked the
        // real name. openProject must now carry the actual GraffitiProject.name into uiState.
        val project = GraffitiProject(id = "1", name = "My Mural")
        coEvery { repository.loadProject("1") } returns Result.success(Unit)

        viewModel.openProject(project)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("1", viewModel.uiState.value.currentProjectId)
        assertEquals("My Mural", viewModel.uiState.value.currentProjectName)
        assertEquals(DashboardViewModel.DESTINATION_EDITOR, viewModel.navigationTrigger.value)
    }

    @Test
    fun `openProject failure never fires navigation and leaves current project untouched`() = runTest {
        // A failed load used to still let the caller navigate into the editor unconditionally.
        // Navigation must now be gated on success.
        val project = GraffitiProject(id = "missing", name = "Ghost")
        coEvery { repository.loadProject("missing") } returns Result.failure(Exception("not found"))
        coEvery { repository.getProjects() } returns emptyList()

        viewModel.openProject(project)
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.navigationTrigger.value)
        assertNull(viewModel.uiState.value.currentProjectId)
        assertNull(viewModel.uiState.value.currentProjectName)
    }

    @Test
    fun `openProject supersedes a still in-flight previous load`() = runTest {
        // Two rapid taps on different library cards must not race: whichever finishes last used to
        // win regardless of which was tapped last. The first load's coroutine is cancelled once a
        // second openProject call comes in, so only the most recently tapped project can win.
        val first = GraffitiProject(id = "1", name = "First")
        val second = GraffitiProject(id = "2", name = "Second")
        coEvery { repository.loadProject("1") } coAnswers {
            kotlinx.coroutines.delay(1_000)
            Result.success(Unit)
        }
        coEvery { repository.loadProject("2") } returns Result.success(Unit)

        viewModel.openProject(first)
        viewModel.openProject(second)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("2", viewModel.uiState.value.currentProjectId)
        assertEquals("Second", viewModel.uiState.value.currentProjectName)
    }

    @Test
    fun `onProjectRenamed keeps the tracked project name in sync after an explicit save`() {
        viewModel.onProjectRenamed("Renamed Mural")
        assertEquals("Renamed Mural", viewModel.uiState.value.currentProjectName)
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
        assertEquals(false, viewModel.uiState.value.isCreatingProject)
        assertEquals("new", viewModel.uiState.value.currentProjectId)
        assertEquals("Test Project", viewModel.uiState.value.currentProjectName)
    }

    @Test
    fun `onCreateProject ignores a re-entrant call while a create is already in flight`() = runTest {
        // The new-project dialog stays visible across the async createProject call, so a second tap
        // before the first finishes used to spawn a duplicate project. isCreatingProject flips
        // synchronously before the coroutine is dispatched, so a second call issued immediately after
        // (as a double tap would) must be a no-op.
        val newProject = GraffitiProject(id = "new", name = "Test Project")
        coEvery { repository.createProject(any<String>()) } returns newProject

        viewModel.onCreateProject(name = "Test Project")
        viewModel.onCreateProject(name = "Test Project")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.createProject("Test Project") }
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

    // ── importProject failure handling ──────────────────────────────────────────

    @Test
    fun `importProject success clears any error and reloads the project list`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        val imported = GraffitiProject(id = "1", name = "Imported")
        // Matched with any() rather than the exact `uri` instance: android.net.Uri's stubbed
        // equals()/hashCode() on a relaxed mock don't behave like value equality, so an exact-argument
        // matcher can silently miss and fall through to mockk's own relaxed default instead of this
        // stub — any() sidesteps that entirely.
        coEvery { repository.importProject(any()) } returns Result.success(imported)
        coEvery { repository.getProjects() } returns listOf(imported)

        viewModel.importProject(uri)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(imported), viewModel.uiState.value.availableProjects)
        assertNull(viewModel.uiState.value.importErrorMessage)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `importProject failure surfaces a user-facing error instead of failing silently`() = runTest {
        // Previously result.isSuccess == false had no failure branch at all — not a toast, not a log
        // line — so a corrupt file just made the spinner stop with no explanation.
        val uri = mockk<Uri>(relaxed = true)
        coEvery { repository.importProject(any()) } returns Result.failure(Exception("bad zip"))

        viewModel.importProject(uri)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(DashboardViewModel.IMPORT_FAILURE_MESSAGE, viewModel.uiState.value.importErrorMessage)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `importProject thrown exception also surfaces a user-facing error`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        coEvery { repository.importProject(any()) } throws RuntimeException("zip bomb rejected")

        viewModel.importProject(uri)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(DashboardViewModel.IMPORT_FAILURE_MESSAGE, viewModel.uiState.value.importErrorMessage)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `dismissImportError clears the message once it has been shown`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        coEvery { repository.importProject(any()) } returns Result.failure(Exception("bad zip"))
        viewModel.importProject(uri)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.dismissImportError()

        assertNull(viewModel.uiState.value.importErrorMessage)
    }
}
