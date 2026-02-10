package com.hereliesaz.graffitixr

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.hardware.SensorManager
import android.net.Uri
import com.hereliesaz.graffitixr.common.util.ProjectManager
import io.mockk.Runs
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
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
    private val application: Application = mockk(relaxed = true)
    private val prefs: SharedPreferences = mockk(relaxed = true)
    private val editor: SharedPreferences.Editor = mockk(relaxed = true)
    private val sensorManager: SensorManager = mockk(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } returns mockk()

        every { application.getSharedPreferences(any(), any()) } returns prefs
        every { prefs.edit() } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        every { editor.apply() } just Runs
        every { prefs.getBoolean("is_right_handed", true) } returns true

        every { application.getSystemService(Context.SENSOR_SERVICE) } returns sensorManager

        viewModel = MainViewModel(application, projectManager)
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

    @Test
    fun `autoSaveProject calls projectManager`() = runTest {
        val context = mockk<Context>(relaxed = true)
        viewModel.onNewProject() // Ensure we have a project ID

        // Wait for coroutine to process onNewProject if needed, but it's using state update which is sync or quick
        // Actually MainViewModel uses viewModelScope.launch.
        // We need to advance time or wait. runTest handles this usually.
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.autoSaveProject(context)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { projectManager.saveProject(context, any(), any(), any()) }
    }

    @Test
    fun `setHandedness updates state`() = runTest {
        // Default is true (Right handed)
        assertEquals(true, viewModel.uiState.value.isRightHanded)

        viewModel.setHandedness(false)
        assertEquals(false, viewModel.uiState.value.isRightHanded)
    }
}
