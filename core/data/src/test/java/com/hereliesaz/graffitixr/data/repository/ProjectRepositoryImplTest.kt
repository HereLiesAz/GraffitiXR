package com.hereliesaz.graffitixr.data.repository

import android.content.Context
import com.hereliesaz.graffitixr.common.DispatcherProvider
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.data.ProjectManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectRepositoryImplTest {

    private val testDispatcher = StandardTestDispatcher()
    private val dispatcherProvider = object : DispatcherProvider {
        override val main: CoroutineDispatcher = testDispatcher
        override val io: CoroutineDispatcher = testDispatcher
        override val default: CoroutineDispatcher = testDispatcher
        override val unconfined: CoroutineDispatcher = testDispatcher
    }

    @Test
    fun `createProject by name adds to state and calls manager`() = runTest(testDispatcher) {
        val mockManager = mockk<ProjectManager>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        val repo = ProjectRepositoryImpl(context, mockManager)

        val project = repo.createProject("Test Project")
        
        assertEquals("Test Project", project.name)
        coVerify { mockManager.saveProject(context, any(), any()) }
    }

    @Test
    fun `getProject calls manager and returns project`() = runTest(testDispatcher) {
        val mockManager = mockk<ProjectManager>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        val repo = ProjectRepositoryImpl(context, mockManager)
        
        val p = GraffitiProject(id = "none", name = "Test")
        coEvery { mockManager.loadProjectMetadata(context, any()) } returns p
        val result = repo.getProject("none")
        assertEquals(p, result)
    }

    @Test
    fun `deleteProject calls manager and updates state`() = runTest(testDispatcher) {
        val mockManager = mockk<ProjectManager>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        val repo = ProjectRepositoryImpl(context, mockManager)
        
        repo.deleteProject("1")
        coVerify { mockManager.deleteProject(context, "1") }
    }
}
