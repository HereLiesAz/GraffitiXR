package com.hereliesaz.graffitixr.feature.ar

import android.graphics.Bitmap
import android.net.Uri
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.Ignore

@OptIn(ExperimentalCoroutinesApi::class)
class ArViewModelTest {

    private lateinit var viewModel: ArViewModel
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        viewModel = ArViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    @Ignore("Feature removed or not implemented in ViewModel yet")
    fun `togglePointCloud toggles state`() = runTest {
        // Initial state is true by default in ArUiState definition
        // val initialState = viewModel.uiState.value.showPointCloud
        
        // viewModel.togglePointCloud()
        // assertEquals(!initialState, viewModel.uiState.value.showPointCloud)
        
        // viewModel.togglePointCloud()
        // assertEquals(initialState, viewModel.uiState.value.showPointCloud)
    }

    @Test
    fun `toggleFlashlight toggles state`() = runTest {
        // Initial state is false
        assertEquals(false, viewModel.uiState.value.isFlashlightOn)

        viewModel.toggleFlashlight()
        assertEquals(true, viewModel.uiState.value.isFlashlightOn)

        viewModel.toggleFlashlight()
        assertEquals(false, viewModel.uiState.value.isFlashlightOn)
    }

    @Test
    @Ignore("Logic changed in ViewModel")
    fun `onFrameCaptured adds uri and bitmap to state`() = runTest {
        /*
        val bitmap = mockk<Bitmap>()
        val uri = mockk<Uri>()
        
        viewModel.onFrameCaptured(bitmap, uri)
        
        testDispatcher.scheduler.advanceUntilIdle()
        
        val state = viewModel.uiState.value
        assert(state.capturedTargetUris.contains(uri))
        assert(state.capturedTargetImages.contains(bitmap))
        */
    }

    @Test
    @Ignore("Function removed")
    fun `onTargetDetected updates state`() = runTest {
        /*
        assertEquals(false, viewModel.uiState.value.isTargetDetected)

        viewModel.onTargetDetected(true)
        assertEquals(true, viewModel.uiState.value.isTargetDetected)

        viewModel.onTargetDetected(false)
        assertEquals(false, viewModel.uiState.value.isTargetDetected)
        */
    }

    @Test
    fun `updateTrackingState updates target detected`() = runTest {
        assertEquals(false, viewModel.uiState.value.isTargetDetected)

        viewModel.updateTrackingState("Tracking", 1, 100)
        assertEquals(true, viewModel.uiState.value.isTargetDetected)
        assertEquals("Tracking", viewModel.uiState.value.trackingState)

        viewModel.updateTrackingState("Searching", 0, 0)
        assertEquals(false, viewModel.uiState.value.isTargetDetected)
        assertEquals("Searching", viewModel.uiState.value.trackingState)
    }
}
