package com.hereliesaz.graffitixr.feature.editor

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.data.ProjectManager
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
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
    private val currentProjectFlow = kotlinx.coroutines.flow.MutableStateFlow<com.hereliesaz.graffitixr.common.model.GraffitiProject?>(null)
    private val context: Context = mockk(relaxed = true)
    private val backgroundRemover: BackgroundRemover = mockk(relaxed = true)
    private val projectManager: ProjectManager = mockk(relaxed = true)
    private val slamManager: SlamManager = mockk(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { projectRepository.currentProject } returns currentProjectFlow
        
        // Mock static methods for Bitmap and Uri
        mockkStatic(BitmapFactory::class)
        mockkStatic(Uri::class)
        
        val mockBitmap = mockk<Bitmap>(relaxed = true)
        every { mockBitmap.width } returns 100
        every { mockBitmap.height } returns 100
        every { BitmapFactory.decodeStream(any()) } returns mockBitmap
        
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

        viewModel = EditorViewModel(projectRepository, projectManager, context, backgroundRemover, slamManager, testDispatcherProvider)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(BitmapFactory::class)
        unmockkStatic(Uri::class)
    }

    @Test
    fun `initial state is correct`() = runTest {
        val state = viewModel.uiState.value
        assertEquals(EditorMode.AR, state.editorMode)
        assertTrue(state.layers.isEmpty())
        assertFalse(state.isLoading)
    }

    @Test
    fun `setEditorMode updates state`() = runTest {
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
        assertEquals("Layer 1", state.layers.first().name)
        assertNotNull(state.activeLayerId)
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
        
        val initialScale = viewModel.uiState.value.layers.first().scale
        viewModel.onScaleChanged(2.0f)
        
        val updatedLayer = viewModel.uiState.value.layers.first()
        assertEquals(initialScale * 2.0f, updatedLayer.scale, 0.01f)
    }

    @Test
    fun `onOffsetChanged updates active layer`() = runTest {
        val uri = Uri.parse("content://test/image.png")
        viewModel.onAddLayer(uri)
        testDispatcher.scheduler.advanceUntilIdle()
        
        val initialOffset = viewModel.uiState.value.layers.first().offset
        val delta = Offset(10f, 20f)
        viewModel.onOffsetChanged(delta)
        
        val updatedLayer = viewModel.uiState.value.layers.first()
        assertEquals(initialOffset + delta, updatedLayer.offset)
    }

    @Test
    fun `onRemoveBackgroundClicked calls backgroundRemover and saves artifact`() = runTest {
        // Mock current project for ID
        val project = GraffitiProject(id = "test-proj", name = "Test")
        currentProjectFlow.value = project
        testDispatcher.scheduler.advanceUntilIdle()

        val uri = Uri.parse("content://test/image.png")
        viewModel.onAddLayer(uri)
        testDispatcher.scheduler.advanceUntilIdle()
        
        // Mock successful background removal
        val resultBitmap = mockk<Bitmap>(relaxed = true)
        coEvery { backgroundRemover.removeBackground(any<Bitmap>()) } returns Result.success(resultBitmap)
        coEvery { projectRepository.saveArtifact(any(), any(), any()) } returns "/path/to/artifact.png"
        
        viewModel.onRemoveBackgroundClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        
        coVerify { backgroundRemover.removeBackground(any<Bitmap>()) }
        coVerify { projectRepository.saveArtifact("test-proj", any(), any()) }
        assertFalse(viewModel.uiState.value.isLoading)
        // Verify URI was updated
        val actualUri = viewModel.uiState.value.layers.first().uri.toString()
        assertEquals("file:///path/to/artifact.png", actualUri)
    }

    @Test
    fun `onLineDrawingClicked calls ImageProcessor and saves artifact`() = runTest {
        // Mock current project for ID
        val project = GraffitiProject(id = "test-proj", name = "Test")
        currentProjectFlow.value = project
        testDispatcher.scheduler.advanceUntilIdle()

        val uri = Uri.parse("content://test/image.png")
        viewModel.onAddLayer(uri)
        testDispatcher.scheduler.advanceUntilIdle()
        
        // Mock successful edge detection
        mockkObject(com.hereliesaz.graffitixr.common.util.ImageProcessor)
        val resultBitmap = mockk<Bitmap>(relaxed = true)
        every { com.hereliesaz.graffitixr.common.util.ImageProcessor.detectEdges(any<Bitmap>()) } returns resultBitmap

        coEvery { projectRepository.saveArtifact(any(), any(), any()) } returns "/path/to/line.png"
        
        viewModel.onLineDrawingClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        
        verify { com.hereliesaz.graffitixr.common.util.ImageProcessor.detectEdges(any<Bitmap>()) }
        coVerify { projectRepository.saveArtifact("test-proj", any(), any()) }
        assertFalse(viewModel.uiState.value.isLoading)
        // Verify URI was updated
        val actualUri = viewModel.uiState.value.layers.first().uri.toString()
        assertEquals("file:///path/to/line.png", actualUri)

        unmockkObject(com.hereliesaz.graffitixr.common.util.ImageProcessor)
    }
    
    @Test
    fun `toggleImageLock updates state`() = runTest {
        val uri = Uri.parse("content://test/image.png")
        viewModel.onAddLayer(uri)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.toggleImageLock()
        assertTrue(viewModel.uiState.value.layers.first().isImageLocked)
        
        viewModel.toggleImageLock()
        assertFalse(viewModel.uiState.value.layers.first().isImageLocked)
    }

    @Test
    fun `saveProject calls createProject when no project exists`() = runTest {
        // Mock no current project
        currentProjectFlow.value = null
        testDispatcher.scheduler.advanceUntilIdle()
        
        val uri = Uri.parse("content://test/image.png")
        viewModel.onAddLayer(uri)
        testDispatcher.scheduler.advanceUntilIdle()
        
        viewModel.saveProject()
        testDispatcher.scheduler.advanceUntilIdle()
        
        coVerify { projectRepository.createProject(any<com.hereliesaz.graffitixr.common.model.GraffitiProject>()) }
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
        // Mock existing project
        val existingProject = GraffitiProject(id = "test-proj", name = "Test")
        currentProjectFlow.value = existingProject
        testDispatcher.scheduler.advanceUntilIdle()
        
        viewModel.saveProject()
        testDispatcher.scheduler.advanceUntilIdle()
        
        coVerify { projectRepository.updateProject(any<com.hereliesaz.graffitixr.common.model.GraffitiProject>()) }
    }

    @Test
    fun `undo restores previous state`() = runTest {
        val uri = Uri.parse("content://test/image.png")
        viewModel.onAddLayer(uri)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.layers.size)

        viewModel.onUndoClicked()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.layers.size)
    }

    @Test
    fun `redo restores undone state`() = runTest {
        val uri = Uri.parse("content://test/image.png")
        viewModel.onAddLayer(uri)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onUndoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, viewModel.uiState.value.layers.size)

        viewModel.onRedoClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.layers.size)
    }

    @Test
    fun `gesture undo restores state`() = runTest {
        val uri = Uri.parse("content://test/image.png")
        viewModel.onAddLayer(uri)
        testDispatcher.scheduler.advanceUntilIdle()

        val initialScale = viewModel.uiState.value.layers.first().scale

        // Start gesture (pushes history)
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
}