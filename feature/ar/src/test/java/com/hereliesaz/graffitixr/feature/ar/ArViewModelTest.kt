package com.hereliesaz.graffitixr.feature.ar

import android.graphics.Bitmap
import android.net.Uri
import io.mockk.mockk
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

import com.hereliesaz.graffitixr.domain.repository.ProjectRepository

@OptIn(ExperimentalCoroutinesApi::class)
class ArViewModelTest {

    private lateinit var viewModel: ArViewModel
    private val projectRepository: ProjectRepository = mockk(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        viewModel = ArViewModel(projectRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is correct`() = runTest {
        val state = viewModel.uiState.first()
        assertFalse(state.isFlashlightOn)
        assertTrue(state.showPointCloud)
        assertNull(state.tempCaptureBitmap)
        assertFalse(state.isTargetDetected)
    }

    @Test
    fun `toggleFlashlight updates state`() = runTest {
        viewModel.toggleFlashlight()
        assertTrue(viewModel.uiState.value.isFlashlightOn)

        viewModel.toggleFlashlight()
        assertFalse(viewModel.uiState.value.isFlashlightOn)
    }

    @Test
    fun `togglePointCloud updates state`() = runTest {
        viewModel.togglePointCloud()
        assertFalse(viewModel.uiState.value.showPointCloud)

        viewModel.togglePointCloud()
        assertTrue(viewModel.uiState.value.showPointCloud)
    }

    @Test
    fun `setTempCapture updates bitmap`() = runTest {
        val bitmap = mockk<Bitmap>()
        viewModel.setTempCapture(bitmap)
        assertEquals(bitmap, viewModel.uiState.value.tempCaptureBitmap)
    }

    @Test
    fun `resetCapture clears bitmap`() = runTest {
        val bitmap = mockk<Bitmap>()
        viewModel.setTempCapture(bitmap)
        viewModel.resetCapture()
        assertNull(viewModel.uiState.value.tempCaptureBitmap)
    }

    @Test
    fun `onFrameCaptured updates state`() = runTest {
        val bitmap = mockk<Bitmap>()
        val uri = mockk<Uri>()
        
        viewModel.onFrameCaptured(bitmap, uri)
        
        val state = viewModel.uiState.value
        assertNull(state.tempCaptureBitmap)
        assertTrue(state.isTargetDetected)
        assertTrue(state.capturedTargetUris.contains(uri))
        assertTrue(state.capturedTargetImages.contains(bitmap))
    }

    @Test
    fun `onTargetDetected updates state`() = runTest {
        viewModel.onTargetDetected(true)
        assertTrue(viewModel.uiState.value.isTargetDetected)

        viewModel.onTargetDetected(false)
        assertFalse(viewModel.uiState.value.isTargetDetected)
    }
}