package com.hereliesaz.graffitixr

import com.hereliesaz.graffitixr.common.model.CaptureStep
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

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private lateinit var viewModel: MainViewModel
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        viewModel = MainViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is correct`() = runTest {
        val state = viewModel.uiState.value
        assertEquals(false, state.isTouchLocked)
        assertEquals(false, state.isCapturingTarget)
    }

    @Test
    fun `startTargetCapture updates state`() = runTest {
        viewModel.startTargetCapture()

        val state = viewModel.uiState.value
        assertEquals(true, state.isCapturingTarget)
        assertEquals(CaptureStep.CAPTURE, state.captureStep)
    }

    @Test
    fun `setTouchLocked updates state`() = runTest {
        viewModel.setTouchLocked(true)
        assertEquals(true, viewModel.uiState.value.isTouchLocked)

        viewModel.setTouchLocked(false)
        assertEquals(false, viewModel.uiState.value.isTouchLocked)
    }

    @Test
    fun `setCaptureStep updates state`() = runTest {
        viewModel.setCaptureStep(CaptureStep.REVIEW)
        assertEquals(CaptureStep.REVIEW, viewModel.uiState.value.captureStep)
    }
}
