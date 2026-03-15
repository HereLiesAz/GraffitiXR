package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.viewModelScope
import com.google.ar.core.Session
import kotlinx.coroutines.cancel
import com.hereliesaz.graffitixr.common.model.ArScanMode
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import com.hereliesaz.graffitixr.nativebridge.depth.StereoDepthProvider
import com.hereliesaz.graffitixr.common.util.isolateMarkings
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ArViewModelTest {

    private lateinit var viewModel: ArViewModel
    private val slamManager: SlamManager = mockk(relaxed = true)
    private val stereoProvider: StereoDepthProvider = mockk(relaxed = true)
    private val projectRepository: ProjectRepository = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk(relaxed = true)
    private val session: Session = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        val fakeBitmap = mockk<Bitmap>(relaxed = true)
        every { fakeBitmap.width } returns 100
        every { fakeBitmap.height } returns 100
        mockkStatic("com.hereliesaz.graffitixr.common.util.ImageExtKt")
        every { any<Bitmap>().isolateMarkings() } returns fakeBitmap
        every { settingsRepository.arScanMode } returns flowOf(ArScanMode.CLOUD_POINTS)
        every { settingsRepository.isRightHanded } returns flowOf(true)
        every { settingsRepository.showAnchorBoundary } returns flowOf(false)
        every { settingsRepository.isImperialUnits } returns flowOf(false)
        every { projectRepository.currentProject } returns MutableStateFlow(null)
        every { context.filesDir } returns File("/tmp")
        viewModel = ArViewModel(slamManager, stereoProvider, projectRepository, settingsRepository, context)
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        // Allow any in-flight Dispatchers.Default coroutines to complete or observe cancellation
        // before removing static mocks, preventing UncaughtExceptionsBeforeTest.
        Thread.sleep(100)
        Dispatchers.resetMain()
        unmockkStatic("com.hereliesaz.graffitixr.common.util.ImageExtKt")
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
        viewModel.toggleFlashlight()
        assertFalse(viewModel.uiState.value.isFlashlightOn)
    }

    @Test
    fun `setTrackingState with true sets correct state`() = runTest {
        viewModel.setTrackingState(true, 0, false)

        val state = viewModel.uiState.value
        assertTrue(state.isScanning)
    }

    @Test
    fun `setTrackingState with false sets correct state`() = runTest {
        viewModel.setTrackingState(false, 0, false)

        val state = viewModel.uiState.value
        assertFalse(state.isScanning)
    }

    @Test
    fun `setTrackingState propagates isDepthApiSupported`() = runTest {
        viewModel.setTrackingState(true, 100, true)
        assertTrue(viewModel.uiState.value.isDepthApiSupported)

        viewModel.setTrackingState(true, 100, false)
        assertFalse(viewModel.uiState.value.isDepthApiSupported)
    }

    // Skipping ensureEngineInitialized test because SlamManager loads native library in init block
    // which causes UnsatisfiedLinkError in JVM unit tests.

    // ==================== Capture Workflow Tests ====================

    @Test
    fun `setTempCapture stores bitmap in state`() = runTest {
        val mockBitmap = mockk<Bitmap>(relaxed = true)

        assertNull(viewModel.uiState.value.tempCaptureBitmap)
        viewModel.setTempCapture(mockBitmap)

        assertEquals(mockBitmap, viewModel.uiState.value.tempCaptureBitmap)
    }

    @Test
    fun `onCaptureConsumed clears temp capture`() = runTest {
        val mockBitmap = mockk<Bitmap>(relaxed = true)
        viewModel.setTempCapture(mockBitmap)
        assertNotNull(viewModel.uiState.value.tempCaptureBitmap)

        viewModel.onCaptureConsumed()
        assertNull(viewModel.uiState.value.tempCaptureBitmap)
    }

    @Test
    fun `setUnwarpPoints updates state`() = runTest {
        val points = listOf(Offset(0f, 0f), Offset(100f, 0f), Offset(100f, 100f), Offset(0f, 100f))

        assertTrue(viewModel.uiState.value.unwarpPoints.isEmpty())
        viewModel.setUnwarpPoints(points)

        assertEquals(4, viewModel.uiState.value.unwarpPoints.size)
        assertEquals(points, viewModel.uiState.value.unwarpPoints)
    }

    @Test
    fun `setActiveUnwarpPoint updates index`() = runTest {
        assertEquals(-1, viewModel.uiState.value.activeUnwarpPointIndex)

        viewModel.setActiveUnwarpPoint(2)
        assertEquals(2, viewModel.uiState.value.activeUnwarpPointIndex)
    }

    @Test
    fun `setMagnifierPosition updates position`() = runTest {
        val position = Offset(150f, 200f)

        assertEquals(Offset.Zero, viewModel.uiState.value.magnifierPosition)
        viewModel.setMagnifierPosition(position)

        assertEquals(position, viewModel.uiState.value.magnifierPosition)
    }

    @Test
    fun `requestCapture sets flag`() = runTest {
        assertFalse(viewModel.uiState.value.isCaptureRequested)

        viewModel.requestCapture()
        assertTrue(viewModel.uiState.value.isCaptureRequested)
    }

    @Test
    fun `onCaptureRequestHandled clears isCaptureRequested flag`() = runTest {
        viewModel.requestCapture()
        assertTrue(viewModel.uiState.value.isCaptureRequested)

        viewModel.onCaptureRequestHandled()
        assertFalse(viewModel.uiState.value.isCaptureRequested)
    }

    @Test
    fun `onScreenTap triggers capture request and onTargetCaptured clears it`() = runTest {
        assertFalse(viewModel.uiState.value.isCaptureRequested)

        viewModel.onScreenTap(0.5f, 0.5f)
        assertTrue(viewModel.uiState.value.isCaptureRequested)

        // Simulate frame arrival for a tap (non-null depth buffer to trigger logic)
        val bmp = mockk<Bitmap>(relaxed = true).also { every { it.width } returns 100; every { it.height } returns 100 }
        viewModel.onTargetCaptured(
            bitmap = bmp,
            depthBuffer = java.nio.ByteBuffer.allocate(10),
            colorW = 100, colorH = 100,
            depthBufW = 100, depthBufH = 100, depthBufStride = 200,
            intrinsics = null,
            viewMatrix = FloatArray(16),
            displayRotation = 0
        )

        assertFalse(viewModel.uiState.value.isCaptureRequested)
        assertEquals(1, viewModel.uiState.value.tapHighlightKeypoints.size)
    }

    @Test
    fun `clearTapHighlights clears keypoints and bitmaps`() = runTest {
        viewModel.clearTapHighlights()
        val state = viewModel.uiState.value
        assertTrue(state.tapHighlightKeypoints.isEmpty())
        assertNull(state.annotatedCaptureBitmap)
        assertNull(state.tempCaptureBitmap)
    }

    // ==================== Session Lifecycle Tests ====================

    @Test
    fun `session resumes only when in AR mode and activity is resumed`() = runTest {
        setPrivateField(viewModel, "session", session)
        assertFalse(getPrivateField(viewModel, "isSessionResumed") as Boolean)

        // Enter AR mode, but activity is paused
        viewModel.setArMode(true, context)
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(getPrivateField(viewModel, "isSessionResumed") as Boolean)

        // Resume activity
        viewModel.onActivityResumed()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(getPrivateField(viewModel, "isSessionResumed") as Boolean)
        verify { session.resume() }
    }

    @Test
    fun `session pauses when activity is paused or not in AR mode`() = runTest {
        setPrivateField(viewModel, "session", session)
        viewModel.setArMode(true, context)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onActivityResumed()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(getPrivateField(viewModel, "isSessionResumed") as Boolean)

        // Pause activity
        viewModel.onActivityPaused()
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(getPrivateField(viewModel, "isSessionResumed") as Boolean)
        verify { session.pause() }

        // Resume activity, then exit AR mode
        viewModel.onActivityResumed()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(getPrivateField(viewModel, "isSessionResumed") as Boolean)
        viewModel.setArMode(false, context)
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(getPrivateField(viewModel, "isSessionResumed") as Boolean)
        verify { session.pause() }
    }

    private fun setPrivateField(obj: Any, fieldName: String, value: Any?) {
        val field = obj.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(obj, value)
    }

    private fun getPrivateField(obj: Any, fieldName: String): Any? {
        val field = obj.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(obj)
    }
}
