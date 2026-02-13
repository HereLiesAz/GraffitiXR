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

    /*
    @Test
    fun `togglePointCloud toggles state`() = runTest {
        // Feature removed or not implemented in ViewModel yet
    }
    */

    @Test
    fun `toggleFlashlight toggles state`() = runTest {
        // Initial state is false
        assertEquals(false, viewModel.uiState.value.isFlashlightOn)

        viewModel.toggleFlashlight()
        assertEquals(true, viewModel.uiState.value.isFlashlightOn)

        viewModel.toggleFlashlight()
        assertEquals(false, viewModel.uiState.value.isFlashlightOn)
    }

    /*
    @Test
    fun `onFrameCaptured adds uri and bitmap to state`() = runTest {
        // Logic changed in ViewModel
    }
    */

    @Test
    fun `updateTrackingState updates target detected`() = runTest {
        assertEquals(false, viewModel.uiState.value.isTargetDetected)

        viewModel.updateTrackingState(true, 1.0f)
        assertEquals(true, viewModel.uiState.value.isTargetDetected)
        assertEquals("Tracking", viewModel.uiState.value.trackingState)

        viewModel.updateTrackingState(false, 0.0f)
        assertEquals(false, viewModel.uiState.value.isTargetDetected)
        assertEquals("Searching", viewModel.uiState.value.trackingState)
    }
}
