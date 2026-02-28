package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import android.hardware.camera2.CameraManager
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import io.mockk.every
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer

@OptIn(ExperimentalCoroutinesApi::class)
class ArViewModelTest {

    private lateinit var viewModel: ArViewModel
    private val slamManager: SlamManager = mockk(relaxed = true)
    private val projectRepository: ProjectRepository = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)
    private val cameraManager: CameraManager = mockk(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { context.getSystemService(Context.CAMERA_SERVICE) } returns cameraManager
        every { cameraManager.cameraIdList } returns arrayOf("0")
        viewModel = ArViewModel(slamManager, projectRepository, context)
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
    fun `toggleFlashlight updates flashlight state`() = runTest {
        assertFalse(viewModel.uiState.value.isFlashlightOn)
        viewModel.toggleFlashlight()
        assertTrue(viewModel.uiState.value.isFlashlightOn)
    }

    @Test
    fun `initEngine calls slamManager`() = runTest {
        viewModel.initEngine()
        verify { slamManager.initialize() }
    }

    @Test
    fun `captureKeyframe calls slamManager`() = runTest {
        io.mockk.every { context.filesDir } returns java.io.File(System.getProperty("java.io.tmpdir")!!)
        io.mockk.every { slamManager.saveKeyframe(any(), any()) } returns true
        viewModel.captureKeyframe()
        verify { slamManager.saveKeyframe(any(), any()) }
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
