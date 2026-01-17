package com.hereliesaz.graffitixr

import android.net.Uri
import com.hereliesaz.graffitixr.utils.ProjectManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
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
    private val projectManager: ProjectManager = mockk(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } returns mockk()

        viewModel = MainViewModel(projectManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Uri::class)
    }

    @Test
    fun `initial state is empty`() = runTest {
        val state = viewModel.uiState.value
        assertEquals(0, state.layers.size)
    }

    @Test
    fun `onOpacityChanged updates active layer`() = runTest {
        val mockUri = mockk<Uri>()
        viewModel.onOverlayImageSelected(mockUri)

        // Default opacity is 1.0f
        assertEquals(1f, viewModel.uiState.value.layers.first().opacity, 0.0f)

        viewModel.onOpacityChanged(0.5f)
        assertEquals(0.5f, viewModel.uiState.value.layers.first().opacity, 0.0f)
    }

    @Test
    fun `onColorBalanceRChanged updates state`() = runTest {
        viewModel.onColorBalanceRChanged(1.2f)
        assertEquals(1.2f, viewModel.uiState.value.colorBalanceR, 0.0f)
    }

    @Test
    fun `onColorBalanceRChanged updates state`() = runTest {
        viewModel.onColorBalanceRChanged(1.2f)
        assertEquals(1.2f, viewModel.uiState.value.colorBalanceR, 0.0f)
    }

    @Test
    fun `undo works correctly`() = runTest {
        val mockUri = mockk<Uri>()
        viewModel.onOverlayImageSelected(mockUri)

        // Create a history point
        viewModel.onGestureEnd()

        viewModel.onOpacityChanged(0.5f)
        assertEquals(0.5f, viewModel.uiState.value.layers.first().opacity, 0.0f)

        viewModel.onUndoClicked()
        assertEquals(1f, viewModel.uiState.value.layers.first().opacity, 0.0f)
    }
}
