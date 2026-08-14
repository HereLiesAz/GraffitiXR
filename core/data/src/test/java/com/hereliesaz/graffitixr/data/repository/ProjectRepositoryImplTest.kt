package com.hereliesaz.graffitixr.data.repository

import android.content.Context
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.data.ProjectManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CyclicBarrier

class ProjectRepositoryImplTest {

    private val testDispatcher = StandardTestDispatcher()

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

    // --- updateProject(transform): the atomic read-modify-write ---

    @Test
    fun `updateProject transform applies to and persists the current project`() = runTest(testDispatcher) {
        val mockManager = mockk<ProjectManager>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        val repo = ProjectRepositoryImpl(context, mockManager)
        repo.createProject(GraffitiProject(id = "p1", name = "Base"))

        repo.updateProject { current -> current.copy(name = "Merged", progressPercentage = 0.5f) }

        // The IN-MEMORY state reflects the transform...
        assertEquals("Merged", repo.currentProject.value?.name)
        assertEquals(0.5f, repo.currentProject.value?.progressPercentage)
        // ...and what actually got PERSISTED is the transformed project, not the pre-transform one.
        coVerify {
            mockManager.saveProject(context, match<GraffitiProject> { it.name == "Merged" && it.progressPercentage == 0.5f })
        }
    }

    @Test
    fun `updateProject transform is a no-op when there is no current project`() = runTest(testDispatcher) {
        val mockManager = mockk<ProjectManager>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        val repo = ProjectRepositoryImpl(context, mockManager)
        // No createProject/loadProject call — currentProject stays null.

        repo.updateProject { current -> current.copy(name = "Should never apply") }

        assertEquals(null, repo.currentProject.value)
        coVerify(exactly = 0) { mockManager.saveProject(context, any(), any()) }
    }

    /**
     * The property "atomic" is supposed to guarantee: two writers transforming NON-overlapping
     * fields concurrently must both land in the final state — neither read-modify-write may clobber
     * the other (this is what `updateProject(transform)`'s `_currentProject.updateAndGet { }` CAS
     * loop plus `saveMutex` exist for — see the comments on `ProjectRepositoryImpl.updateProject`).
     *
     * Uses real OS threads (not the deterministic test dispatcher) released simultaneously by a
     * [CyclicBarrier], so a naive non-atomic "read current, compute copy, write current" would
     * actually be able to race and silently drop one side's change.
     */
    @Test
    fun `updateProject transform merges concurrent non-overlapping writes without losing either`() {
        val mockManager = mockk<ProjectManager>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        val repo = ProjectRepositoryImpl(context, mockManager)
        runBlocking { repo.createProject(GraffitiProject(id = "race", name = "Base")) }

        val barrier = CyclicBarrier(2)
        val threadA = Thread {
            barrier.await()
            runBlocking { repo.updateProject { current -> current.copy(name = "FromA") } }
        }
        val threadB = Thread {
            barrier.await()
            runBlocking { repo.updateProject { current -> current.copy(progressPercentage = 0.77f) } }
        }
        threadA.start()
        threadB.start()
        threadA.join(5_000)
        threadB.join(5_000)

        val final = repo.currentProject.value
        assertEquals("FromA", final?.name)
        assertEquals(0.77f, final?.progressPercentage ?: -1f, 0.0001f)
    }

    /**
     * A higher-volume version of the above: many concurrent transforms each append ONE unique
     * refinement path (a non-overlapping, additive field). If the merge ever dropped an update, the
     * final list would be shorter than the number of writers — this asserts none are lost.
     */
    @Test
    fun `updateProject transform under heavy concurrency loses no writes`() {
        val mockManager = mockk<ProjectManager>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        val repo = ProjectRepositoryImpl(context, mockManager)
        runBlocking { repo.createProject(GraffitiProject(id = "race-many", name = "Base")) }

        val writerCount = 40
        val barrier = CyclicBarrier(writerCount)
        val threads = (0 until writerCount).map { i ->
            Thread {
                barrier.await()
                runBlocking {
                    repo.updateProject { current ->
                        current.copy(drawingPaths = current.drawingPaths + listOf(listOf(i.toFloat() to i.toFloat())))
                    }
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(10_000) }

        val final = repo.currentProject.value
        assertEquals(writerCount, final?.drawingPaths?.size)
        // Every writer's distinct path made it in exactly once.
        val firstXs = final?.drawingPaths?.map { it.first().first }?.toSet()
        assertTrue(firstXs?.size == writerCount)
    }
}
