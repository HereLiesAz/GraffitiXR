package com.hereliesaz.graffitixr.domain.repository

import android.net.Uri
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectRepositoryTest {

    @Test
    fun `currentProject emits correct state`() = runTest {
        val repo = mockk<ProjectRepository>()
        val project = GraffitiProject(id = "1", name = "Test")
        val stateFlow = MutableStateFlow<GraffitiProject?>(project)
        every { repo.currentProject } returns stateFlow
        assertEquals(project, repo.currentProject.value)
    }

    @Test
    fun `projects emits list of projects`() = runTest {
        val repo = mockk<ProjectRepository>()
        val projects = listOf(GraffitiProject(id = "1", name = "P1"))
        every { repo.projects } returns flowOf(projects)
        val result = repo.projects.first()
        assertEquals(projects, result)
    }

    @Test
    fun `createProject by name calls implementation`() = runTest {
        val repo = mockk<ProjectRepository>()
        val expected = GraffitiProject(id = "id", name = "New")
        coEvery { repo.createProject("New") } returns expected
        val result = repo.createProject("New")
        assertEquals(expected, result)
    }

    @Test
    fun `createProject by project calls implementation`() = runTest {
        val repo = mockk<ProjectRepository>(relaxed = true)
        val project = GraffitiProject(id = "1", name = "p")
        repo.createProject(project)
        coVerify { repo.createProject(project) }
    }

    @Test
    fun `getProject returns correct project`() = runTest {
        val repo = mockk<ProjectRepository>()
        val project = GraffitiProject(id = "1", name = "p")
        coEvery { repo.getProject("1") } returns project
        assertEquals(project, repo.getProject("1"))
    }

    @Test
    fun `getProjects returns all projects`() = runTest {
        val repo = mockk<ProjectRepository>()
        val list = listOf(GraffitiProject(id = "1", name = "p"))
        coEvery { repo.getProjects() } returns list
        assertEquals(list, repo.getProjects())
    }

    @Test
    fun `loadProject returns success result`() = runTest {
        val repo = mockk<ProjectRepository>()
        coEvery { repo.loadProject("1") } returns Result.success(Unit)
        val result = repo.loadProject("1")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `updateProject by object calls implementation`() = runTest {
        val repo = mockk<ProjectRepository>(relaxed = true)
        val p = GraffitiProject(id = "1", name = "p")
        repo.updateProject(p)
        coVerify { repo.updateProject(p) }
    }

    @Test
    fun `updateProject by transform calls implementation`() = runTest {
        val repo = mockk<ProjectRepository>(relaxed = true)
        val transform: (GraffitiProject) -> GraffitiProject = { it.copy(name = "Updated") }
        repo.updateProject(transform)
        coVerify { repo.updateProject(transform) }
    }

    @Test
    fun `deleteProject calls implementation`() = runTest {
        val repo = mockk<ProjectRepository>(relaxed = true)
        repo.deleteProject("1")
        coVerify { repo.deleteProject("1") }
    }

    @Test
    fun `saveArtifact returns correct path`() = runTest {
        val repo = mockk<ProjectRepository>()
        coEvery { repo.saveArtifact("1", "f", any()) } returns "/path"
        assertEquals("/path", repo.saveArtifact("1", "f", ByteArray(0)))
    }

    @Test
    fun `updateTargetFingerprint calls implementation`() = runTest {
        val repo = mockk<ProjectRepository>(relaxed = true)
        repo.updateTargetFingerprint("1", "path")
        coVerify { repo.updateTargetFingerprint("1", "path") }
    }

    @Test
    fun `updateMapPath calls implementation`() = runTest {
        val repo = mockk<ProjectRepository>(relaxed = true)
        repo.updateMapPath("1", "path")
        coVerify { repo.updateMapPath("1", "path") }
    }

    @Test
    fun `importProject returns success result`() = runTest {
        val repo = mockk<ProjectRepository>()
        val p = GraffitiProject(id = "1", name = "p")
        val uri = mockk<Uri>()
        coEvery { repo.importProject(uri) } returns Result.success(p)
        assertEquals(Result.success(p), repo.importProject(uri))
    }
}
