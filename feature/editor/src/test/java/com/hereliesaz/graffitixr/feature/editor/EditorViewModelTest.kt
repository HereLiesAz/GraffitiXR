package com.hereliesaz.graffitixr.feature.editor

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.data.ProjectManager
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
import com.hereliesaz.graffitixr.common.coop.OpEmitter
import com.hereliesaz.graffitixr.common.util.NativeLibLoader
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

import com.hereliesaz.graffitixr.common.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher

@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest {

    private lateinit var viewModel: EditorViewModel
    private val projectRepository: ProjectRepository = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk(relaxed = true)
    private val currentProjectFlow = kotlinx.coroutines.flow.MutableStateFlow<GraffitiProject?>(null)
    private val context: Context = mockk(relaxed = true)
    private val projectManager: ProjectManager = mockk(relaxed = true)
    private val exportManager: com.hereliesaz.graffitixr.feature.editor.export.ExportManager = mockk(relaxed = true)
    private val slamManager: SlamManager = mockk(relaxed = true)
    private val opEmitter: OpEmitter = mockk(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        // NativeLibLoader.loadAll() throws on a host JVM (the .so is Android-arm only); no-op it
        // for any object that still calls it from an init block.
        mockkObject(NativeLibLoader)
        every { NativeLibLoader.loadAll() } returns Unit
        // Emit a test project so projectId is non-null, enabling onAddLayer to work
        val testProject = GraffitiProject(id = "test-project")
        currentProjectFlow.value = testProject
        every { projectRepository.currentProject } returns currentProjectFlow
        every { settingsRepository.backgroundColor } returns kotlinx.coroutines.flow.flowOf(0xFF000000.toInt())

        // Mock static methods for Bitmap, Uri, and Toast
        mockkStatic(BitmapFactory::class)
        mockkStatic(android.graphics.Bitmap::class)
        mockkStatic(Uri::class)
        mockkStatic(Toast::class)
        every { Toast.makeText(any(), any<String>(), any()) } returns mockk(relaxed = true)
        mockkObject(com.hereliesaz.graffitixr.common.util.ImageUtils)

        val mockBitmap = mockk<Bitmap>(relaxed = true)
        every { mockBitmap.width } returns 100
        every { mockBitmap.height } returns 100
        every { mockBitmap.copy(any(), any()) } returns mockBitmap
        every { BitmapFactory.decodeStream(any()) } returns mockBitmap
        every { android.graphics.Bitmap.createBitmap(any<Int>(), any<Int>(), any()) } returns mockBitmap

        // Mock ImageUtils so ImageDecoder/BitmapFactory isn't invoked in unit tests
        coEvery { com.hereliesaz.graffitixr.common.util.ImageUtils.getBitmapDimensions(any(), any()) } returns Pair(100, 100)
        coEvery { com.hereliesaz.graffitixr.common.util.ImageUtils.loadBitmapAsync(any(), any(), any()) } returns mockBitmap
        coEvery { projectRepository.saveArtifact(any(), any(), any()) } returns "/path/to/artifact.png"
        every { com.hereliesaz.graffitixr.common.util.ImageUtils.bitmapToByteArray(any()) } returns ByteArray(0)

        every { Uri.parse(any()) } answers {
            val uriString = it.invocation.args[0] as String
            val mUri = mockk<Uri>()
            every { mUri.toString() } returns uriString
            every { mUri.scheme } returns if (uriString.contains("://")) uriString.split("://")[0] else null
            every { mUri.path } returns if (uriString.contains("://")) uriString.split("://")[1] else uriString
            mUri
        }

        // Mock Context and ContentResolver
        val contentResolver = mockk<ContentResolver>()
        val inputStream = ByteArrayInputStream(ByteArray(0))
        every { context.contentResolver } returns contentResolver
        every { contentResolver.openInputStream(any()) } returns inputStream

        val testDispatcherProvider = object : DispatcherProvider {
            override val main: CoroutineDispatcher = testDispatcher
            override val io: CoroutineDispatcher = testDispatcher
            override val default: CoroutineDispatcher = testDispatcher
            override val unconfined: CoroutineDispatcher = testDispatcher
        }

        viewModel = EditorViewModel(
            projectRepository, settingsRepository, projectManager, exportManager, context,
            slamManager, testDispatcherProvider, opEmitter
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(BitmapFactory::class)
        unmockkStatic(android.graphics.Bitmap::class)
        unmockkStatic(Uri::class)
        unmockkStatic(Toast::class)
        unmockkObject(com.hereliesaz.graffitixr.common.util.ImageUtils)
        unmockkObject(NativeLibLoader)
    }

    /** Imports an image, which is the only way a design comes into being. */
    private fun addDesign() {
        viewModel.onAddLayer(Uri.parse("content://test/image.png"))
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `initial state is correct`() {
        val state = viewModel.uiState.value
        assertEquals(EditorMode.AR, state.editorMode)
        assertNull(state.design)
    }

    @Test
    fun `setEditorMode updates state`() {
        viewModel.setEditorMode(EditorMode.MOCKUP)
        assertEquals(EditorMode.MOCKUP, viewModel.uiState.value.editorMode)
    }

    @Test
    fun `onAddLayer sets the design`() = runTest {
        addDesign()
        assertNotNull(viewModel.uiState.value.design)
    }

    @Test
    fun `onAddLayer replaces the previous design rather than accumulating`() = runTest {
        // There is exactly one design. Importing a second image is how the artist changes it, not
        // how they add to it — the old multi-layer model is gone.
        addDesign()
        val first = viewModel.uiState.value.design!!.id
        addDesign()
        val second = viewModel.uiState.value.design!!.id
        assertNotEqualsId(first, second)
    }

    private fun assertNotEqualsId(a: String, b: String) =
        assertTrue("expected a fresh design id, both were '$a'", a != b)

    @Test
    fun `onScaleChanged updates the design`() = runTest {
        addDesign()
        viewModel.onScaleChanged(2.0f)
        assertEquals(2.0f, viewModel.uiState.value.design!!.scale)
    }

    @Test
    fun `onOffsetChanged accumulates onto the design offset`() = runTest {
        addDesign()
        viewModel.onOffsetChanged(Offset(10f, 20f))
        assertEquals(Offset(10f, 20f), viewModel.uiState.value.design!!.offset)
    }

    @Test
    fun `toggleImageLock updates state`() = runTest {
        addDesign()
        assertFalse(viewModel.uiState.value.design!!.isImageLocked)
        viewModel.toggleImageLock()
        assertTrue(viewModel.uiState.value.design!!.isImageLocked)
    }

    @Test
    fun `onCycleRotationAxis walks the axis and raises feedback`() = runTest {
        addDesign()
        val start = viewModel.uiState.value.activeRotationAxis
        viewModel.onCycleRotationAxis()
        assertTrue(viewModel.uiState.value.activeRotationAxis != start)
        assertTrue(viewModel.uiState.value.showRotationAxisFeedback)
        viewModel.onFeedbackShown()
        assertFalse(viewModel.uiState.value.showRotationAxisFeedback)
    }

    @Test
    fun `saveProject calls createProject when no project exists`() = runTest {
        currentProjectFlow.value = null
        addDesign()

        viewModel.saveProject()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { projectRepository.createProject(any<GraffitiProject>()) }
    }

    @Test
    fun `saveProject calls updateProject when project exists`() = runTest {
        addDesign()

        viewModel.saveProject()
        testDispatcher.scheduler.advanceUntilIdle()

        // Updates now go through the atomic transform overload (read-modify-write) so a concurrent
        // AR wall-map save can't clobber the design edits — see the save-race fix.
        coVerify { projectRepository.updateProject(any<(GraffitiProject) -> GraffitiProject>()) }
    }

    @Test
    fun `undo restores previous state`() = runTest {
        addDesign()
        assertNotNull(viewModel.uiState.value.design)

        viewModel.onUndoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(viewModel.uiState.value.design)
    }

    @Test
    fun `redo restores undone state`() = runTest {
        addDesign()

        viewModel.onUndoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(viewModel.uiState.value.design)

        viewModel.onRedoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.design)
    }

    @Test
    fun `gesture undo restores state`() = runTest {
        viewModel.setEditorMode(EditorMode.DESIGN)
        addDesign()

        val initialScale = viewModel.uiState.value.design!!.scale

        viewModel.onGestureStart()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onTransformGesture(Offset.Zero, 2.0f, 0f)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(initialScale * 2.0f, viewModel.uiState.value.design!!.scale, 0.01f)

        viewModel.onGestureEnd()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onUndoClicked()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(initialScale, viewModel.uiState.value.design!!.scale, 0.01f)
    }

    @Test
    fun `undo and redo on empty stacks do not crash`() {
        // Fresh ViewModel has empty undo and redo stacks; neither call should throw.
        viewModel.onUndoClicked()
        viewModel.onRedoClicked()

        val state = viewModel.uiState.value
        assertEquals(0, state.undoCount)
        assertEquals(0, state.redoCount)
        assertNull(state.design)
    }

    // ==================== Adjustment knobs: design vs mode ====================
    // The knobs behave differently depending on the mode: in DESIGN they tone the design itself, in
    // any projected mode they tone that mode's whole-design ModeAdjustment. Both paths must keep
    // working now that there is only one design.

    @Test
    fun `onOpacityChanged in Design updates the design`() = runTest {
        viewModel.setEditorMode(EditorMode.DESIGN)
        addDesign()
        viewModel.onOpacityChanged(0.25f)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(0.25f, viewModel.uiState.value.design!!.opacity)
    }

    @Test
    fun `onOpacityChanged in a Mode updates the mode adjustment, not the design`() = runTest {
        viewModel.setEditorMode(EditorMode.OVERLAY)
        addDesign()
        viewModel.onOpacityChanged(0.4f)
        testDispatcher.scheduler.advanceUntilIdle()
        val st = viewModel.uiState.value
        assertEquals(0.4f, st.modeAdjustments[EditorMode.OVERLAY]?.opacity)
        assertEquals(1.0f, st.design!!.opacity)
    }

    @Test
    fun `onBrightnessChanged in Design updates the design`() = runTest {
        viewModel.setEditorMode(EditorMode.DESIGN)
        addDesign()
        viewModel.onBrightnessChanged(0.3f)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(0.3f, viewModel.uiState.value.design!!.brightness)
    }

    @Test
    fun `color balance knobs always tone the design`() = runTest {
        // Unlike opacity/brightness/contrast/saturation, colour balance has no per-mode equivalent,
        // so it goes to the design regardless of the mode in view.
        viewModel.setEditorMode(EditorMode.OVERLAY)
        addDesign()
        viewModel.onColorBalanceRChanged(1.5f)
        viewModel.onColorBalanceGChanged(0.5f)
        viewModel.onColorBalanceBChanged(0.75f)
        testDispatcher.scheduler.advanceUntilIdle()
        val d = viewModel.uiState.value.design!!
        assertEquals(1.5f, d.colorBalanceR)
        assertEquals(0.5f, d.colorBalanceG)
        assertEquals(0.75f, d.colorBalanceB)
    }

    @Test
    fun `reset flattens placement and a second press restores it`() = runTest {
        viewModel.setEditorMode(EditorMode.DESIGN)
        addDesign()
        viewModel.onScaleChanged(2.5f)
        viewModel.onOffsetChanged(Offset(30f, 40f))
        viewModel.onOpacityChanged(0.4f)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onResetClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        var d = viewModel.uiState.value.design!!
        assertEquals(1f, d.scale)
        assertEquals(Offset.Zero, d.offset)
        assertEquals("reset must not touch the knobs", 0.4f, d.opacity)

        viewModel.onResetClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        d = viewModel.uiState.value.design!!
        assertEquals(2.5f, d.scale)
        assertEquals(Offset(30f, 40f), d.offset)
        assertEquals(0.4f, d.opacity)
    }

    @Test
    fun `knob changes before an image is chosen do not crash`() = runTest {
        viewModel.setEditorMode(EditorMode.DESIGN)
        viewModel.onOpacityChanged(0.5f)
        viewModel.onToggleInvert()
        viewModel.toggleImageLock()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(viewModel.uiState.value.design)
    }
}
