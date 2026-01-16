package com.hereliesaz.graffitixr

import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.SavedStateHandle
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
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
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private lateinit var viewModel: MainViewModel
    private val application: Application = mockk(relaxed = true)
    private val savedStateHandle: SavedStateHandle = SavedStateHandle()
    private val sharedPreferences: SharedPreferences = mockk(relaxed = true)
    private val editor: SharedPreferences.Editor = mockk(relaxed = true)

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(android.util.Log::class)
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0

        every { application.applicationContext } returns application
        every { application.getSharedPreferences(any(), any()) } returns sharedPreferences
        every { sharedPreferences.edit() } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        every { editor.apply() } returns Unit
        every { sharedPreferences.getBoolean(any(), any()) } returns false

        // Mock filesDir for ProjectManager
        val tempDir = File.createTempFile("temp", "dir").parentFile
        every { application.filesDir } returns tempDir

        // Initialize ViewModel with the new constructor (no ARCoreManager)
        viewModel = MainViewModel(application, savedStateHandle)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is correct`() = runTest {
        val state = viewModel.uiState.value
        assertEquals(1f, state.opacity, 0.0f)
        assertEquals(1f, state.contrast, 0.0f)
        assertEquals(1f, state.saturation, 0.0f)
    }

    @Test
    fun `onOpacityChanged updates state`() = runTest {
        viewModel.onOpacityChanged(0.5f)
        assertEquals(0.5f, viewModel.uiState.value.opacity, 0.0f)
    }

    @Test
    fun `onContrastChanged updates state`() = runTest {
        viewModel.onContrastChanged(1.5f)
        assertEquals(1.5f, viewModel.uiState.value.contrast, 0.0f)
    }

    @Test
    fun `onSaturationChanged updates state`() = runTest {
        viewModel.onSaturationChanged(0.2f)
        assertEquals(0.2f, viewModel.uiState.value.saturation, 0.0f)
    }

    @Test
    fun `undo works correctly`() = runTest {
        viewModel.onGestureStart()
        viewModel.onOpacityChanged(0.5f)
        assertEquals(0.5f, viewModel.uiState.value.opacity, 0.0f)

        viewModel.onUndoClicked()
        assertEquals(1f, viewModel.uiState.value.opacity, 0.0f)
    }
}