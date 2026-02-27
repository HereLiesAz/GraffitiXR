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
import org.junit.Assert.assertFalse
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
        assertFalse(state.isScanning)
        assertFalse(state.isFlashlightOn)
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
    fun `captureKeyframe calls slamManager`() = runTest {
        io.mockk.every { slamManager.saveKeyframe(any()) } returns true
        viewModel.captureKeyframe()
        verify { slamManager.saveKeyframe(any()) }
    }

    @Test
    fun `onFrameAvailable calls slamManager`() = runTest {
        val buffer = ByteBuffer.allocate(0)
        val w = 640
        val h = 480
        viewModel.onFrameAvailable(buffer, w, h)
        verify { slamManager.feedMonocularData(buffer, w, h) }
    }
}
