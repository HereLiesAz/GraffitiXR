package com.hereliesaz.graffitixr.feature.ar

import com.hereliesaz.graffitixr.common.model.OverlayLayer
import com.hereliesaz.graffitixr.common.model.ProjectData
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArViewModelTest {

    private lateinit var viewModel: ArViewModel
    private val projectRepository: ProjectRepository = mockk(relaxed = true)
    private val renderer: ArRenderer = mockk(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()
    
    private val projectFlow = MutableStateFlow<ProjectData?>(null)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { projectRepository.currentProject } returns projectFlow
        
        viewModel = ArViewModel(projectRepository)
        viewModel.arRenderer = renderer
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `togglePointCloud updates state and renderer`() = runTest {
        // Initial state
        assertEquals(false, viewModel.uiState.value.showPointCloud)
        
        viewModel.togglePointCloud()
        
        assertEquals(true, viewModel.uiState.value.showPointCloud)
        verify { renderer.showPointCloud = true }
    }

    @Test
    fun `updateLayers from repository updates renderer`() = runTest {
        val layer = mockk<OverlayLayer>(relaxed = true)
        val project = mockk<ProjectData>(relaxed = true)
        every { project.layers } returns listOf(layer)
        
        projectFlow.value = project
        
        testDispatcher.scheduler.advanceUntilIdle()
        
        verify { renderer.updateLayers(listOf(layer)) }
    }
}
