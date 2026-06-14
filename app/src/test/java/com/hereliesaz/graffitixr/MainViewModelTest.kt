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
        // The do-it-to-advance walkthrough tests below were written against a tutorial-mode-OFF
        // start and toggle it on themselves. Production now defaults the mode ON (so the coach
        // surfaces automatically at launch), so normalize back to off here. The production default
        // is covered separately by `tutorial mode is on by default`.
        if (viewModel.uiState.value.tutorialModeActive) viewModel.toggleTutorialMode()
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
    fun `tutorial mode is on by default`() = runTest {
        // A fresh ViewModel (not the setup() one, which normalizes the mode off) must start with
        // tutorial mode enabled so the onboarding coach surfaces automatically at launch.
        val fresh = MainViewModel(projectRepository, slamManager, projectManager, settingsRepository, context)
        assertEquals(true, fresh.uiState.value.tutorialModeActive)
    }

    // --- Do-it-to-advance walkthrough ---------------------------------------------------------

    /** A small sequence: a single-line step, then a two-line step, then another single-line step. */
    private fun sampleSequence() = listOf(
        TutorialStep("mode.host", listOf("pick a mode")),
        TutorialStep("design.host", listOf("design line 1", "design line 2")),
        TutorialStep("project.host.main", listOf("manage your project")),
    )

    @Test
    fun `setTutorialSequence is ignored while tutorial mode is off`() = runTest {
        viewModel.setTutorialSequence(sampleSequence())
        assertEquals(emptyList<TutorialStep>(), viewModel.uiState.value.tutorialSteps)
    }

    @Test
    fun `onRailInteraction does nothing while tutorial mode is off`() = runTest {
        // Even with a sequence installed, no advancement happens unless the mode is on.
        viewModel.onRailInteraction("mode.host")
        assertEquals(0, viewModel.uiState.value.tutorialStepIndex)
    }

    @Test
    fun `matching interaction advances to the next step`() = runTest {
        viewModel.toggleTutorialMode()
        viewModel.setTutorialSequence(sampleSequence())

        viewModel.onRailInteraction("mode.host")

        val state = viewModel.uiState.value
        assertEquals(1, state.tutorialStepIndex)
        assertEquals(0, state.tutorialLineIndex)
    }

    @Test
    fun `matching interaction walks lines before moving to the next step`() = runTest {
        viewModel.toggleTutorialMode()
        viewModel.setTutorialSequence(sampleSequence())
        viewModel.onRailInteraction("mode.host") // -> step 1 (design.host), line 0

        // design.host has two lines: first matching interaction bumps the line, not the step.
        viewModel.onRailInteraction("design.host")
        var state = viewModel.uiState.value
        assertEquals(1, state.tutorialStepIndex)
        assertEquals(1, state.tutorialLineIndex)

        // Second one exhausts the lines and moves to the final step.
        viewModel.onRailInteraction("design.host")
        state = viewModel.uiState.value
        assertEquals(2, state.tutorialStepIndex)
        assertEquals(0, state.tutorialLineIndex)
    }

    @Test
    fun `non-matching interaction is ignored`() = runTest {
        viewModel.toggleTutorialMode()
        viewModel.setTutorialSequence(sampleSequence())

        viewModel.onRailInteraction("project.host.main") // not the current target (mode.host)

        val state = viewModel.uiState.value
        assertEquals(0, state.tutorialStepIndex)
        assertEquals(0, state.tutorialLineIndex)
    }

    @Test
    fun `advanceTutorialIdle advances without an id match`() = runTest {
        viewModel.toggleTutorialMode()
        viewModel.setTutorialSequence(sampleSequence())

        viewModel.advanceTutorialIdle()

        assertEquals(1, viewModel.uiState.value.tutorialStepIndex)
    }

    @Test
    fun `advancing past the last step clears the sequence but keeps the mode on`() = runTest {
        viewModel.toggleTutorialMode()
        viewModel.setTutorialSequence(sampleSequence())

        // mode.host -> design(line0) -> design(line1) -> project -> (end)
        repeat(5) { viewModel.advanceTutorialIdle() }

        val state = viewModel.uiState.value
        assertEquals(emptyList<TutorialStep>(), state.tutorialSteps)
        assertEquals(0, state.tutorialStepIndex)
        assertEquals(0, state.tutorialLineIndex)
        assertEquals(true, state.tutorialModeActive)
    }

    @Test
    fun `setTutorialSequence preserves position when the current target still exists`() = runTest {
        viewModel.toggleTutorialMode()
        viewModel.setTutorialSequence(sampleSequence())
        viewModel.onRailInteraction("mode.host") // now on design.host (index 1)

        // A mode/layer change rebuilds the sequence; design.host is still present at a new index.
        viewModel.setTutorialSequence(
            listOf(
                TutorialStep("project.host.main", listOf("manage your project")),
                TutorialStep("design.host", listOf("design line 1", "design line 2")),
            )
        )

        val state = viewModel.uiState.value
        assertEquals("design.host", state.tutorialSteps[state.tutorialStepIndex].targetId)
        assertEquals(1, state.tutorialStepIndex)
    }

    @Test
    fun `setTutorialSequence restarts at step 0 when the current target is gone`() = runTest {
        viewModel.toggleTutorialMode()
        viewModel.setTutorialSequence(sampleSequence())
        viewModel.onRailInteraction("mode.host") // on design.host

        // New sequence no longer contains design.host -> restart for predictability.
        viewModel.setTutorialSequence(
            listOf(TutorialStep("project.host.main", listOf("manage your project")))
        )

        val state = viewModel.uiState.value
        assertEquals(0, state.tutorialStepIndex)
        assertEquals(0, state.tutorialLineIndex)
    }

    @Test
    fun `dismissCurrentTutorial clears the sequence`() = runTest {
        viewModel.toggleTutorialMode()
        viewModel.setTutorialSequence(sampleSequence())

        viewModel.dismissCurrentTutorial()

        assertEquals(emptyList<TutorialStep>(), viewModel.uiState.value.tutorialSteps)
    }

    @Test
    fun `toggleTutorialMode off resets walkthrough state`() = runTest {
        viewModel.toggleTutorialMode()
        viewModel.setTutorialSequence(sampleSequence())
        viewModel.onRailInteraction("mode.host")

        viewModel.toggleTutorialMode()

        val state = viewModel.uiState.value
        assertEquals(false, state.tutorialModeActive)
        assertEquals(emptyList<TutorialStep>(), state.tutorialSteps)
        assertEquals(0, state.tutorialStepIndex)
        assertEquals(0, state.tutorialLineIndex)
    }
}
