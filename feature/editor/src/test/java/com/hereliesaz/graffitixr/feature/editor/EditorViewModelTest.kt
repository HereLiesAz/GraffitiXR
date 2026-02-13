package com.hereliesaz.graffitixr.feature.editor

import android.content.Context
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.EditorPanel
import com.hereliesaz.graffitixr.common.model.RotationAxis
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest {

    private lateinit var viewModel: EditorViewModel
    private lateinit var projectRepository: ProjectRepository
    private lateinit var context: Context
    private lateinit var backgroundRemover: BackgroundRemover
    private lateinit var slamManager: SlamManager
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        projectRepository = mockk(relaxed = true)
        context = mockk(relaxed = true)
        backgroundRemover = mockk(relaxed = true)
        slamManager = mockk(relaxed = true)
        viewModel = EditorViewModel(projectRepository, context, backgroundRemover, slamManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `setEditorMode updates state`() = runTest {
        assertEquals(EditorMode.EDIT, viewModel.uiState.value.editorMode) // Default

        viewModel.setEditorMode(EditorMode.DRAW)
        assertEquals(EditorMode.DRAW, viewModel.uiState.value.editorMode)
    }

    @Test
    fun `toggleImageLock toggles state`() = runTest {
        assertFalse(viewModel.uiState.value.isImageLocked)

        viewModel.toggleImageLock()
        assertTrue(viewModel.uiState.value.isImageLocked)

        viewModel.toggleImageLock()
        assertFalse(viewModel.uiState.value.isImageLocked)
    }

    @Test
    fun `toggleHandedness toggles state`() = runTest {
        // Default isRightHanded = true
        assertTrue(viewModel.uiState.value.isRightHanded)

        viewModel.toggleHandedness()
        assertFalse(viewModel.uiState.value.isRightHanded)
    }

    @Test
    fun `onCycleRotationAxis cycles through axes`() = runTest {
        assertEquals(RotationAxis.Z, viewModel.uiState.value.activeRotationAxis)

        viewModel.onCycleRotationAxis()
        assertEquals(RotationAxis.X, viewModel.uiState.value.activeRotationAxis)

        viewModel.onCycleRotationAxis()
        assertEquals(RotationAxis.Y, viewModel.uiState.value.activeRotationAxis)

        viewModel.onCycleRotationAxis()
        assertEquals(RotationAxis.Z, viewModel.uiState.value.activeRotationAxis)
    }

    @Test
    fun `onAdjustClicked sets active panel`() = runTest {
        viewModel.onAdjustClicked()
        assertEquals(EditorPanel.ADJUST, viewModel.uiState.value.activePanel)
    }

    @Test
    fun `onColorClicked sets active panel`() = runTest {
        viewModel.onColorClicked()
        assertEquals(EditorPanel.COLOR, viewModel.uiState.value.activePanel)
    }

    @Test
    fun `onExportComplete resets hideUiForCapture`() = runTest {
        viewModel.exportProject()
        assertTrue(viewModel.uiState.value.hideUiForCapture)
        assertTrue(viewModel.exportTrigger.value)

        viewModel.onExportComplete()
        assertFalse(viewModel.uiState.value.hideUiForCapture)
        assertFalse(viewModel.exportTrigger.value)
    }
}
