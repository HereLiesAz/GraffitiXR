package com.hereliesaz.graffitixr

import android.content.Context
import com.hereliesaz.graffitixr.common.model.CaptureStep
import com.hereliesaz.graffitixr.data.ProjectManager
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
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
    private val projectRepository: ProjectRepository = mockk(relaxed = true)
    private val slamManager: SlamManager = mockk(relaxed = true)
    private val projectManager: ProjectManager = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { settingsRepository.completedTutorials } returns flowOf(emptySet<String>())
        viewModel = MainViewModel(projectRepository, slamManager, projectManager, settingsRepository, context)
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
        assertEquals(true, state.isWaitingForTap)
        assertEquals(CaptureStep.NONE, state.captureStep)
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

    @Test
    fun `onRailTap does nothing while tutorial mode is off`() = runTest {
        viewModel.onRailTap("mode.host")
        assertEquals(null, viewModel.uiState.value.currentTutorialId)
    }

    @Test
    fun `onRailTap starts tutorial when none is showing`() = runTest {
        viewModel.toggleTutorialMode()

        viewModel.onRailTap("mode.host")

        val state = viewModel.uiState.value
        assertEquals("mode.host", state.currentTutorialId)
        assertEquals(0, state.currentTutorialStep)
    }

    @Test
    fun `onRailTap advances current tutorial when one is showing`() = runTest {
        viewModel.toggleTutorialMode()
        viewModel.onRailTap("mode.host")

        // A second rail tap advances the showing card rather than replacing it.
        viewModel.onRailTap("design.host")

        val state = viewModel.uiState.value
        assertEquals("mode.host", state.currentTutorialId)
        assertEquals(1, state.currentTutorialStep)
    }

    @Test
    fun `advanceTutorial increments the step`() = runTest {
        viewModel.toggleTutorialMode()
        viewModel.onRailTap("mode.host")

        viewModel.advanceTutorial()
        viewModel.advanceTutorial()

        assertEquals(2, viewModel.uiState.value.currentTutorialStep)
    }

    @Test
    fun `dismissCurrentTutorial clears id and resets step`() = runTest {
        viewModel.toggleTutorialMode()
        viewModel.onRailTap("mode.host")
        viewModel.advanceTutorial()

        viewModel.dismissCurrentTutorial()

        val state = viewModel.uiState.value
        assertEquals(null, state.currentTutorialId)
        assertEquals(0, state.currentTutorialStep)
    }

    @Test
    fun `toggleTutorialMode off resets tutorial state`() = runTest {
        viewModel.toggleTutorialMode()
        viewModel.onRailTap("mode.host")
        viewModel.advanceTutorial()

        viewModel.toggleTutorialMode()

        val state = viewModel.uiState.value
        assertEquals(false, state.tutorialModeActive)
        assertEquals(null, state.currentTutorialId)
        assertEquals(0, state.currentTutorialStep)
    }
}
