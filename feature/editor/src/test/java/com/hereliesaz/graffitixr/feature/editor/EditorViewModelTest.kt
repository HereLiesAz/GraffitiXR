package com.hereliesaz.graffitixr.feature.editor

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.Layer
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
import kotlinx.coroutines.flow.first
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
import java.io.InputStream

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
    private val extensions: com.hereliesaz.graffitixr.data.azphalt.ExtensionRepository = mockk(relaxed = true)
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
            slamManager, testDispatcherProvider, opEmitter, extensions
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

    @Test
    fun `initial state is correct`() {
        val state = viewModel.uiState.value
        assertEquals(EditorMode.AR, state.editorMode)
        assertTrue(state.layers.isEmpty())
        assertNull(state.activeLayerId)
    }

    @Test
    fun `setEditorMode updates state`() {
        viewModel.setEditorMode(EditorMode.MOCKUP)
        assertEquals(EditorMode.MOCKUP, viewModel.uiState.value.editorMode)
    }

    @Test
    fun `onAddLayer adds a layer`() = runTest {
        val uri = Uri.parse("content://test/image.png")
        viewModel.onAddLayer(uri)
        
        testDispatcher.scheduler.advanceUntilIdle()
        
        val state = viewModel.uiState.value
        assertEquals(1, state.layers.size)
        assertNotNull(state.activeLayerId)
        assertEquals(state.layers.first().id, state.activeLayerId)
    }

    @Test
    fun `onLayerActivated updates activeLayerId`() = runTest {
        val uri = Uri.parse("content://test/image.png")
        viewModel.onAddLayer(uri)
        testDispatcher.scheduler.advanceUntilIdle()
        
        val layerId = viewModel.uiState.value.layers.first().id
        viewModel.onLayerActivated(layerId)
        
        assertEquals(layerId, viewModel.uiState.value.activeLayerId)
    }

    @Test
    fun `onScaleChanged updates active layer`() = runTest {
        val uri = Uri.parse("content://test/image.png")
        viewModel.onAddLayer(uri)
        testDispatcher.scheduler.advanceUntilIdle()
        
        val layerId = viewModel.uiState.value.layers.first().id
        viewModel.onLayerActivated(layerId)
        
        viewModel.onScaleChanged(2.0f)
        assertEquals(2.0f, viewModel.uiState.value.layers.first().scale)
    }

    @Test
    fun `onOffsetChanged updates active layer`() = runTest {
        val uri = Uri.parse("content://test/image.png")
        viewModel.onAddLayer(uri)
        testDispatcher.scheduler.advanceUntilIdle()
        
        val layerId = viewModel.uiState.value.layers.first().id
        viewModel.onLayerActivated(layerId)
        
        val newOffset = Offset(10f, 20f)
        viewModel.onOffsetChanged(newOffset)
        assertEquals(newOffset, viewModel.uiState.value.layers.first().offset)
    }

    @Test
    fun `toggleImageLock updates state`() = runTest {
        val uri = Uri.parse("content://test/image.png")
        viewModel.onAddLayer(uri)
        testDispatcher.scheduler.advanceUntilIdle()
        
        val layerId = viewModel.uiState.value.layers.first().id
        viewModel.onLayerActivated(layerId)
        
        assertFalse(viewModel.uiState.value.layers.first().isImageLocked)
        viewModel.toggleImageLock()
        assertTrue(viewModel.uiState.value.layers.first().isImageLocked)
    }

    @Test
    fun `saveProject calls createProject when no project exists`() = runTest {
        currentProjectFlow.value = null
        val uri = Uri.parse("content://test/image.png")
        viewModel.onAddLayer(uri)
        testDispatcher.scheduler.advanceUntilIdle()
        
        viewModel.saveProject()
        testDispatcher.scheduler.advanceUntilIdle()
        
        coVerify { projectRepository.createProject(any<GraffitiProject>()) }
    }

    @Test
    fun `onLayerRemoved removes layer and clears active ID if necessary`() = runTest {
        val uri = Uri.parse("content://test/image.png")
        viewModel.onAddLayer(uri)
        testDispatcher.scheduler.advanceUntilIdle()
        
        val layerId = viewModel.uiState.value.layers.first().id
        viewModel.onLayerRemoved(layerId)
        
        assertTrue(viewModel.uiState.value.layers.isEmpty())
        assertNull(viewModel.uiState.value.activeLayerId)
    }

    @Test
    fun `saveProject calls updateProject when project exists`() = runTest {
        val uri = Uri.parse("content://test/image.png")
        viewModel.onAddLayer(uri)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.saveProject()
        testDispatcher.scheduler.advanceUntilIdle()

        // Updates now go through the atomic transform overload (read-modify-write) so a concurrent
        // AR wall-map save can't clobber the layer edits — see the save-race fix.
        coVerify { projectRepository.updateProject(any<(GraffitiProject) -> GraffitiProject>()) }
    }

    @Test
    fun `undo restores previous state`() = runTest {
        val uri = Uri.parse("content://test/image.png")
        viewModel.onAddLayer(uri)
        testDispatcher.scheduler.advanceUntilIdle()
        
        assertEquals(1, viewModel.uiState.value.layers.size)
        
        viewModel.onUndoClicked()
        assertEquals(0, viewModel.uiState.value.layers.size)
    }

    @Test
    fun `redo restores undone state`() = runTest {
        val uri = Uri.parse("content://test/image.png")
        viewModel.onAddLayer(uri)
        testDispatcher.scheduler.advanceUntilIdle()
        
        viewModel.onUndoClicked()
        assertEquals(0, viewModel.uiState.value.layers.size)
        
        viewModel.onRedoClicked()
        assertEquals(1, viewModel.uiState.value.layers.size)
    }

    @Test
    fun `gesture undo restores state`() = runTest {
        val uri = Uri.parse("content://test/image.png")
        viewModel.onAddLayer(uri)
        testDispatcher.scheduler.advanceUntilIdle()

        val initialScale = viewModel.uiState.value.layers.first().scale
        
        // Start gesture
        viewModel.onGestureStart()
        testDispatcher.scheduler.advanceUntilIdle()

        // Transform
        viewModel.onTransformGesture(Offset.Zero, 2.0f, 0f)
        testDispatcher.scheduler.advanceUntilIdle()

        val modifiedScale = viewModel.uiState.value.layers.first().scale
        assertEquals(initialScale * 2.0f, modifiedScale, 0.01f)

        // End gesture
        viewModel.onGestureEnd()
        testDispatcher.scheduler.advanceUntilIdle()

        // Undo
        viewModel.onUndoClicked()
        testDispatcher.scheduler.advanceUntilIdle()

        val restoredScale = viewModel.uiState.value.layers.first().scale
        assertEquals(initialScale, restoredScale, 0.01f)
    }

    @Test
    fun `undo and redo on empty stacks do not crash`() {
        // Fresh ViewModel has empty undo and redo stacks; neither call should throw.
        viewModel.onUndoClicked()
        viewModel.onRedoClicked()

        val state = viewModel.uiState.value
        assertEquals(0, state.undoCount)
        assertEquals(0, state.redoCount)
        assertTrue(state.layers.isEmpty())
    }

    @Test
    fun `onLayerRemoved with unknown id does not modify state`() = runTest {
        val uri = Uri.parse("content://test/image.png")
        viewModel.onAddLayer(uri)
        testDispatcher.scheduler.advanceUntilIdle()

        val layerCountBefore = viewModel.uiState.value.layers.size
        assertEquals(1, layerCountBefore)

        // Removing a non-existent ID should leave the layer list unchanged.
        viewModel.onLayerRemoved("non-existent-id")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(layerCountBefore, viewModel.uiState.value.layers.size)
    }

    // ==================== Layer-state characterization (refactor safety net) ====================
    // These pin the observable behavior of the layer-management operations a future LayerManager
    // extraction must preserve. They seed state via setLayers() and avoid OpenCV, so they run in
    // plain JVM. If an extraction changes behavior, these go red. advanceUntilIdle() runs FIRST so
    // the init currentProject-collect (which seeds layers from the empty test project) settles
    // before setLayers() seeds the real fixture.

    private fun lyr(id: String, name: String = id) = Layer(id = id, name = name)

    @Test
    fun `characterize onLayerReordered reorders layers by the given id order`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(lyr("a"), lyr("b"), lyr("c")))
        viewModel.onLayerReordered(listOf("c", "a", "b"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("c", "a", "b"), viewModel.uiState.value.layers.map { it.id })
    }

    @Test
    fun `characterize onLayerRenamed renames only the target layer`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(lyr("a", "Alpha"), lyr("b", "Beta")))
        viewModel.onLayerRenamed("a", "Renamed")
        testDispatcher.scheduler.advanceUntilIdle()
        val layers = viewModel.uiState.value.layers
        assertEquals("Renamed", layers.first { it.id == "a" }.name)
        assertEquals("Beta", layers.first { it.id == "b" }.name)
    }

    @Test
    fun `characterize onToggleVisibility flips only the target layer`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setLayers(listOf(lyr("a"), lyr("b")))
        viewModel.onToggleVisibility("a")
        testDispatcher.scheduler.advanceUntilIdle()
        val layers = viewModel.uiState.value.layers
        assertFalse(layers.first { it.id == "a" }.isVisible)
        assertTrue(layers.first { it.id == "b" }.isVisible)
    }

    @Test
    fun `onOpacityChanged in Design updates only the active layer`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setEditorMode(EditorMode.DESIGN)
        viewModel.setLayers(listOf(lyr("a"), lyr("b")))
        viewModel.onLayerActivated("a")
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onOpacityChanged(0.25f)
        testDispatcher.scheduler.advanceUntilIdle()
        val layers = viewModel.uiState.value.layers
        assertEquals(0.25f, layers.first { it.id == "a" }.opacity)
        assertEquals(1.0f, layers.first { it.id == "b" }.opacity)
    }

    @Test
    fun `onOpacityChanged in a Mode updates the mode adjustment, not the layer`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setEditorMode(EditorMode.OVERLAY)
        viewModel.setLayers(listOf(lyr("a")))
        viewModel.onLayerActivated("a")
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onOpacityChanged(0.4f)
        testDispatcher.scheduler.advanceUntilIdle()
        val st = viewModel.uiState.value
        // Whole-design mode opacity is updated; the active layer's own opacity is untouched.
        assertEquals(0.4f, st.modeAdjustments[EditorMode.OVERLAY]?.opacity)
        assertEquals(1.0f, st.layers.first { it.id == "a" }.opacity)
    }

}
