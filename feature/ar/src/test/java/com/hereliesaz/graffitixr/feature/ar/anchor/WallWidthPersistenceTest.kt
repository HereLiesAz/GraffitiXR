package com.hereliesaz.graffitixr.feature.ar.anchor

import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WallWidthPersistenceTest {
    private var stored: GraffitiProject? = null
    private val repo: ProjectRepository = mockk(relaxed = true) {
        coEvery { updateProject(any<(GraffitiProject) -> GraffitiProject>()) } coAnswers {
            stored = firstArg<(GraffitiProject) -> GraffitiProject>()(requireNotNull(stored))
        }
    }

    @Test
    fun `writes the width into the project it was measured in`() = runTest {
        stored = GraffitiProject(id = "p", name = "Wall")
        assertTrue(WallWidthPersistence.save(repo, "p", 3.25f))
        assertEquals(3.25f, stored!!.wallWidthMeters!!, 0f)
    }

    @Test
    fun `a different current project is left alone and reported unsaved`() = runTest {
        stored = GraffitiProject(id = "q", name = "Other")
        assertFalse(WallWidthPersistence.save(repo, "p", 3.25f))
        assertNull(stored!!.wallWidthMeters)
    }

    @Test
    fun `a failed write is reported unsaved`() = runTest {
        stored = GraffitiProject(id = "p", name = "Wall")
        coEvery { repo.updateProject(any<(GraffitiProject) -> GraffitiProject>()) } throws java.io.IOException("disk full")
        assertFalse(WallWidthPersistence.save(repo, "p", 3.25f))
    }
}
