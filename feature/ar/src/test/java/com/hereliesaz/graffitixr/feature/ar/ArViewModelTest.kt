package com.hereliesaz.graffitixr.feature.ar

import com.hereliesaz.graffitixr.common.model.OverlayLayer
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer
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
    
    private val projectFlow = MutableStateFlow<GraffitiProject?>(null)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { projectRepository.currentProject } returns projectFlow
        
        viewModel = ArViewModel(projectRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `togglePointCloud updates state`() = runTest {
        // Initial state
        assertEquals(false, viewModel.uiState.value.showPointCloud)
        
        viewModel.togglePointCloud()
        
        assertEquals(true, viewModel.uiState.value.showPointCloud)
    }

    @Test
    fun `toggleFlashlight updates state`() = runTest {
        // Initial state
        assertEquals(false, viewModel.uiState.value.isFlashlightOn)

        viewModel.toggleFlashlight()

        assertEquals(true, viewModel.uiState.value.isFlashlightOn)
    }

    @Test
    fun `onProgressUpdate updates mappingQualityScore`() = runTest {
        viewModel.onProgressUpdate(0.75f, null)
        assertEquals(0.75f, viewModel.uiState.value.mappingQualityScore)
    }

    @Test
    fun `onFrameCaptured updates state with bitmap and uri`() = runTest {
        val bitmap = mockk<android.graphics.Bitmap>()
        val uri = mockk<android.net.Uri>()
        
        viewModel.onFrameCaptured(bitmap, uri)
        
        testDispatcher.scheduler.advanceUntilIdle()
        
        assertEquals(true, viewModel.uiState.value.isArTargetCreated)
        assert(viewModel.uiState.value.capturedTargetUris.contains(uri))
        assert(viewModel.uiState.value.capturedTargetImages.contains(bitmap))
    }
}
