// FILE: feature/editor/src/test/java/com/hereliesaz/graffitixr/feature/editor/stencil/StencilViewModelTest.kt
package com.hereliesaz.graffitixr.feature.editor.stencil

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.StencilLayerCount
import com.hereliesaz.graffitixr.common.model.StencilWizardStep
import com.hereliesaz.graffitixr.feature.editor.SubjectIsolator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StencilViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var viewModel: StencilViewModel
    private val stencilProcessor: StencilProcessor = mockk(relaxed = true)
    private val printEngine: StencilPrintEngine = mockk(relaxed = true)
    private val subjectIsolator: SubjectIsolator = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)
    private lateinit var mockBitmap: Bitmap

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeLayer(id: String, uri: Uri? = mockk()): Layer = Layer(
        id = id,
        name = "Layer $id",
        uri = uri,
        textParams = null,
        isSketch = false
    )

    private fun makeTextLayer(id: String): Layer = Layer(
        id = id,
        name = "Text $id",
        uri = mockk(),
        textParams = mockk()
    )

    private fun makeSketchLayer(id: String): Layer = Layer(
        id = id,
        name = "Sketch $id",
        uri = mockk(),
        isSketch = true
    )

    private fun makeNoUriLayer(id: String): Layer = Layer(
        id = id,
        name = "NoUri $id",
        uri = null,
        textParams = null,
        isSketch = false
    )

    // ── Setup / Teardown ──────────────────────────────────────────────────────

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        mockkStatic(Log::class)
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0

        mockkObject(com.hereliesaz.graffitixr.common.util.ImageUtils)

        mockBitmap = mockk<Bitmap>(relaxed = true).apply {
            every { width } returns 100
            every { height } returns 100
        }

        coEvery {
            com.hereliesaz.graffitixr.common.util.ImageUtils.loadBitmapAsync(any(), any())
        } returns mockBitmap

        coEvery { subjectIsolator.isolate(any()) } returns Result.success(mockBitmap)

        // Default stencilProcessor: never emits (pipeline not triggered in most tests)
        every { stencilProcessor.process(any(), any()) } returns flowOf()

        viewModel = StencilViewModel(stencilProcessor, printEngine, subjectIsolator, context)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    // ── initForWizard ─────────────────────────────────────────────────────────

    @Test
    fun `initForWizard with single layer auto-advances to ISOLATE`() {
        val layer = makeLayer("layer1")
        viewModel.initForWizard(listOf(layer))

        val state = viewModel.uiState.value
        assertEquals(StencilWizardStep.ISOLATE, state.wizardStep)
        assertEquals("layer1", state.sourceLayerId)
    }

    @Test
    fun `initForWizard with multiple layers stays at PICK_SOURCE`() {
        val layers = listOf(makeLayer("layer1"), makeLayer("layer2"))
        viewModel.initForWizard(layers)

        assertEquals(StencilWizardStep.PICK_SOURCE, viewModel.uiState.value.wizardStep)
    }

    @Test
    fun `initForWizard filters out text layers`() {
        // One text layer + one image layer = one eligible → auto-advance
        val layers = listOf(makeTextLayer("text1"), makeLayer("img1"))
        viewModel.initForWizard(layers)

        assertEquals(StencilWizardStep.ISOLATE, viewModel.uiState.value.wizardStep)
        assertEquals("img1", viewModel.uiState.value.sourceLayerId)
    }

    @Test
    fun `initForWizard filters out sketch layers`() {
        // One sketch + one image = one eligible → auto-advance
        val layers = listOf(makeSketchLayer("sketch1"), makeLayer("img1"))
        viewModel.initForWizard(layers)

        assertEquals(StencilWizardStep.ISOLATE, viewModel.uiState.value.wizardStep)
    }

    @Test
    fun `initForWizard filters out layers with null URI`() {
        // One no-uri + one with uri = one eligible → auto-advance
        val layers = listOf(makeNoUriLayer("noUri1"), makeLayer("img1"))
        viewModel.initForWizard(layers)

        assertEquals(StencilWizardStep.ISOLATE, viewModel.uiState.value.wizardStep)
    }

    @Test
    fun `initForWizard with zero eligible layers stays at PICK_SOURCE`() {
        val layers = listOf(makeTextLayer("text1"), makeSketchLayer("sketch1"))
        viewModel.initForWizard(layers)

        assertEquals(StencilWizardStep.PICK_SOURCE, viewModel.uiState.value.wizardStep)
    }

    // ── onSourceLayerPicked ───────────────────────────────────────────────────

    @Test
    fun `onSourceLayerPicked advances to ISOLATE`() {
        viewModel.initForWizard(listOf(makeLayer("layer1"), makeLayer("layer2")))
        assertEquals(StencilWizardStep.PICK_SOURCE, viewModel.uiState.value.wizardStep)

        viewModel.onSourceLayerPicked("layer1")

        val state = viewModel.uiState.value
        assertEquals(StencilWizardStep.ISOLATE, state.wizardStep)
        assertEquals("layer1", state.sourceLayerId)
    }

    @Test
    fun `onSourceLayerPicked sets correct sourceLayerId`() {
        viewModel.initForWizard(listOf(makeLayer("a"), makeLayer("b")))
        viewModel.onSourceLayerPicked("b")

        assertEquals("b", viewModel.uiState.value.sourceLayerId)
    }

    // ── onIsolateConfirmed ────────────────────────────────────────────────────

    @Test
    fun `onIsolateConfirmed advances to CHOOSE_LAYERS when isolatedBitmap is set`() = runTest {
        viewModel.initForWizard(listOf(makeLayer("layer1")))
        // Trigger isolation to populate isolatedBitmap
        viewModel.onIsolateRequested()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onIsolateConfirmed()

        assertEquals(StencilWizardStep.CHOOSE_LAYERS, viewModel.uiState.value.wizardStep)
    }

    @Test
    fun `onIsolateConfirmed does nothing when isolatedBitmap is null`() {
        viewModel.initForWizard(listOf(makeLayer("layer1")))
        // Do NOT call onIsolateRequested — isolatedBitmap stays null

        viewModel.onIsolateConfirmed()

        // Should remain on ISOLATE (not advance)
        assertEquals(StencilWizardStep.ISOLATE, viewModel.uiState.value.wizardStep)
    }

    // ── onLayerCountChosen ────────────────────────────────────────────────────

    @Test
    fun `onLayerCountChosen advances to GENERATE and triggers pipeline`() = runTest {
        // Set up a real flow to observe pipeline invocation
        var pipelineCalled = false
        every { stencilProcessor.process(any(), any()) } answers {
            pipelineCalled = true
            flowOf()
        }

        // Arrange: get to CHOOSE_LAYERS
        viewModel.initForWizard(listOf(makeLayer("layer1")))
        viewModel.onIsolateRequested()
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onIsolateConfirmed()
        assertEquals(StencilWizardStep.CHOOSE_LAYERS, viewModel.uiState.value.wizardStep)

        viewModel.onLayerCountChosen(StencilLayerCount.TWO)
        testDispatcher.scheduler.advanceUntilIdle()

        // Wizard step transitions to GENERATE immediately
        // (pipeline may advance to PREVIEW upon Done, but at least GENERATE was reached)
        assert(
            viewModel.uiState.value.wizardStep == StencilWizardStep.GENERATE ||
                viewModel.uiState.value.wizardStep == StencilWizardStep.PREVIEW
        )
        assert(viewModel.uiState.value.layerCount == StencilLayerCount.TWO)
        assert(pipelineCalled)
    }

    // ── onBack ────────────────────────────────────────────────────────────────

    @Test
    fun `onBack decrements step correctly`() = runTest {
        // Walk forward through several steps, then walk back

        // PICK_SOURCE → nothing (no back from PICK_SOURCE)
        viewModel.initForWizard(listOf(makeLayer("a"), makeLayer("b")))
        assertEquals(StencilWizardStep.PICK_SOURCE, viewModel.uiState.value.wizardStep)
        viewModel.onBack()
        assertEquals(StencilWizardStep.PICK_SOURCE, viewModel.uiState.value.wizardStep)

        // Advance to ISOLATE
        viewModel.onSourceLayerPicked("a")
        assertEquals(StencilWizardStep.ISOLATE, viewModel.uiState.value.wizardStep)

        // ISOLATE → PICK_SOURCE
        viewModel.onBack()
        assertEquals(StencilWizardStep.PICK_SOURCE, viewModel.uiState.value.wizardStep)

        // Re-advance to CHOOSE_LAYERS
        viewModel.onSourceLayerPicked("a")
        viewModel.onIsolateRequested()
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onIsolateConfirmed()
        assertEquals(StencilWizardStep.CHOOSE_LAYERS, viewModel.uiState.value.wizardStep)

        // CHOOSE_LAYERS → ISOLATE
        viewModel.onBack()
        assertEquals(StencilWizardStep.ISOLATE, viewModel.uiState.value.wizardStep)
    }

    @Test
    fun `onBack from PRINT_EXPORT returns to PREVIEW`() {
        viewModel.onProceedToPrint()
        // Must be on PRINT_EXPORT first (force state via onProceedToPrint)
        // Manually set state up for this test path
        viewModel.onBack()
        assertEquals(StencilWizardStep.PREVIEW, viewModel.uiState.value.wizardStep)
    }

    // ── onRebuild ─────────────────────────────────────────────────────────────

    @Test
    fun `onRebuild clears stencilLayers and returns to CHOOSE_LAYERS`() = runTest {
        // Set up so pipeline emits Done with layers
        val fakeLayers = listOf(
            com.hereliesaz.graffitixr.common.model.StencilLayer(
                type = com.hereliesaz.graffitixr.common.model.StencilLayerType.SILHOUETTE,
                bitmap = mockBitmap
            )
        )
        every { stencilProcessor.process(any(), any()) } returns flowOf(
            StencilProgress.Done(fakeLayers)
        )

        viewModel.initForWizard(listOf(makeLayer("layer1")))
        viewModel.onIsolateRequested()
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onIsolateConfirmed()
        viewModel.onLayerCountChosen(StencilLayerCount.ONE)
        testDispatcher.scheduler.advanceUntilIdle()

        // Should now be on PREVIEW with layers
        assertEquals(StencilWizardStep.PREVIEW, viewModel.uiState.value.wizardStep)
        assert(viewModel.uiState.value.stencilLayers.isNotEmpty())

        viewModel.onRebuild()

        assertEquals(StencilWizardStep.CHOOSE_LAYERS, viewModel.uiState.value.wizardStep)
        assert(viewModel.uiState.value.stencilLayers.isEmpty())
    }

    // ── onProceedToPrint ──────────────────────────────────────────────────────

    @Test
    fun `onProceedToPrint advances to PRINT_EXPORT`() {
        viewModel.onProceedToPrint()
        assertEquals(StencilWizardStep.PRINT_EXPORT, viewModel.uiState.value.wizardStep)
    }

    // ── Pipeline auto-advance ─────────────────────────────────────────────────

    @Test
    fun `pipeline Done event auto-advances to PREVIEW`() = runTest {
        val fakeLayers = listOf(
            com.hereliesaz.graffitixr.common.model.StencilLayer(
                type = com.hereliesaz.graffitixr.common.model.StencilLayerType.SILHOUETTE,
                bitmap = mockBitmap
            )
        )
        every { stencilProcessor.process(any(), any()) } returns flowOf(
            StencilProgress.Done(fakeLayers)
        )

        viewModel.initForWizard(listOf(makeLayer("layer1")))
        viewModel.onIsolateRequested()
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onIsolateConfirmed()
        viewModel.onLayerCountChosen(StencilLayerCount.ONE)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(StencilWizardStep.PREVIEW, viewModel.uiState.value.wizardStep)
        assertEquals(1, viewModel.uiState.value.stencilLayers.size)
    }

    // ── onIsolateRedo ─────────────────────────────────────────────────────────

    @Test
    fun `onIsolateRedo clears isolatedBitmap and re-runs isolation`() = runTest {
        viewModel.initForWizard(listOf(makeLayer("layer1")))
        viewModel.onIsolateRequested()
        testDispatcher.scheduler.advanceUntilIdle()

        // First isolation should have set isolatedBitmap
        assert(viewModel.uiState.value.isolatedBitmap != null)

        // Redo should clear and re-run
        viewModel.onIsolateRedo()
        testDispatcher.scheduler.advanceUntilIdle()

        // Should still have isolatedBitmap after redo completes
        assert(viewModel.uiState.value.isolatedBitmap != null)
    }

    // ── Error handling ────────────────────────────────────────────────────────

    @Test
    fun `onIsolateRequested sets exportError when bitmap load fails`() = runTest {
        coEvery {
            com.hereliesaz.graffitixr.common.util.ImageUtils.loadBitmapAsync(any(), any())
        } returns null

        viewModel.initForWizard(listOf(makeLayer("layer1")))
        viewModel.onIsolateRequested()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assert(state.exportError != null)
        assert(state.isProcessing == false)
    }

    @Test
    fun `onIsolateRequested sets exportError when isolation fails`() = runTest {
        coEvery { subjectIsolator.isolate(any()) } returns Result.failure(Exception("Segmentation failed"))

        viewModel.initForWizard(listOf(makeLayer("layer1")))
        viewModel.onIsolateRequested()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Segmentation failed", state.exportError)
        assertNull(state.isolatedBitmap)
    }
}
