package com.hereliesaz.graffitixr.feature.ar

import com.hereliesaz.graffitixr.nativebridge.SlamManager
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer

@OptIn(ExperimentalCoroutinesApi::class)
class ArViewModelTest {

    private lateinit var viewModel: ArViewModel
    private val slamManager: SlamManager = mockk(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        viewModel = ArViewModel(slamManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is correct`() = runTest {
        val state = viewModel.uiState.first()
        assertFalse(state.gestureInProgress)
        assertEquals(0, state.undoCount)
    }

    @Test
    fun `toggleFlashlight calls slamManager`() = runTest {
        viewModel.toggleFlashlight()
        verify { slamManager.toggleFlashlight() }
    }

    @Test
    fun `initEngine calls slamManager`() = runTest {
        viewModel.initEngine()
        verify { slamManager.initialize() }
    }

    @Test
    fun `captureKeyframe calls slamManager and updates undoCount`() = runTest {
        // Assume saveKeyframe returns true
        io.mockk.every { slamManager.saveKeyframe(any()) } returns true
        
        viewModel.captureKeyframe()

        verify { slamManager.saveKeyframe(any()) }
        
        val state = viewModel.uiState.value
        assertEquals(1, state.undoCount)
    }

    @Test
    fun `onFrameAvailable calls slamManager`() = runTest {
        val buffer = ByteBuffer.allocate(0)
        val w = 640
        val h = 480
        viewModel.onFrameAvailable(buffer, w, h)
        verify { slamManager.feedMonocularData(buffer, w, h) }
    }

    @Test
    fun `setGestureInProgress updates state`() = runTest {
        viewModel.setGestureInProgress(true)
        assertTrue(viewModel.uiState.value.gestureInProgress)

        viewModel.setGestureInProgress(false)
        assertFalse(viewModel.uiState.value.gestureInProgress)
    }
}
