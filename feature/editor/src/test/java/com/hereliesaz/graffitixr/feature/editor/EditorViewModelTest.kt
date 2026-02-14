package com.hereliesaz.graffitixr.feature.editor

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.RotationAxis
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
    private val context: Context = mockk(relaxed = true)
    private val backgroundRemover: BackgroundRemover = mockk(relaxed = true)
    private val slamManager: SlamManager = mockk(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        
        // Mock static methods for Bitmap
        mockkStatic(BitmapFactory::class)
        val mockBitmap = mockk<Bitmap>(relaxed = true)
        every { BitmapFactory.decodeStream(any()) } returns mockBitmap
        
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

        viewModel = EditorViewModel(projectRepository, context, backgroundRemover, slamManager, testDispatcherProvider)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(BitmapFactory::class)
    }

    @Test
    fun `initial state is correct`() = runTest {
        val state = viewModel.uiState.value
        assertEquals(EditorMode.EDIT, state.editorMode)
        assertTrue(state.layers.isEmpty())
        assertFalse(state.isLoading)
    }

    @Test
    fun `setEditorMode updates state`() = runTest {
        viewModel.setEditorMode(EditorMode.TRACE)
        assertEquals(EditorMode.TRACE, viewModel.uiState.value.editorMode)
    }

    @Test
    fun `onAddLayer adds a layer`() = runTest {
        val uri = mockk<Uri>()
        every { uri.toString() } returns "content://test/image.png"
        
        viewModel.onAddLayer(uri)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.layers.size)
        assertEquals("Layer 1", state.layers.first().name)
        assertNotNull(state.activeLayerId)
    }

    @Test
    fun `onLayerActivated updates activeLayerId`() = runTest {
        val uri = mockk<Uri>()
        viewModel.onAddLayer(uri)
        testDispatcher.scheduler.advanceUntilIdle()
        
        val layerId = viewModel.uiState.value.layers.first().id
        viewModel.onLayerActivated(layerId)
        
        assertEquals(layerId, viewModel.uiState.value.activeLayerId)
    }

    @Test
    fun `onScaleChanged updates active layer`() = runTest {
        val uri = mockk<Uri>()
        viewModel.onAddLayer(uri)
        testDispatcher.scheduler.advanceUntilIdle()
        
        val initialScale = viewModel.uiState.value.layers.first().scale
        viewModel.onScaleChanged(2.0f)
        
        val updatedLayer = viewModel.uiState.value.layers.first()
        assertEquals(initialScale * 2.0f, updatedLayer.scale, 0.01f)
    }

    @Test
    fun `onOffsetChanged updates active layer`() = runTest {
        val uri = mockk<Uri>()
        viewModel.onAddLayer(uri)
        testDispatcher.scheduler.advanceUntilIdle()
        
        val initialOffset = viewModel.uiState.value.layers.first().offset
        val delta = Offset(10f, 20f)
        viewModel.onOffsetChanged(delta)
        
        val updatedLayer = viewModel.uiState.value.layers.first()
        assertEquals(initialOffset + delta, updatedLayer.offset)
    }

    @Test
    fun `onRemoveBackgroundClicked calls backgroundRemover`() = runTest {
        val uri = mockk<Uri>()
        viewModel.onAddLayer(uri)
        testDispatcher.scheduler.advanceUntilIdle()
        
        // Mock successful background removal
        val resultBitmap = mockk<Bitmap>()
        coEvery { backgroundRemover.removeBackground(any<Bitmap>()) } returns Result.success(resultBitmap)
        
        viewModel.onRemoveBackgroundClicked()
        testDispatcher.scheduler.advanceUntilIdle()
        
        coEvery { backgroundRemover.removeBackground(any<Bitmap>()) }
        assertFalse(viewModel.uiState.value.isLoading)
    }
    
    @Test
    fun `toggleImageLock updates state`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        viewModel.onAddLayer(uri)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.toggleImageLock()
        assertTrue(viewModel.uiState.value.isImageLocked)
        
        viewModel.toggleImageLock()
        assertFalse(viewModel.uiState.value.isImageLocked)
    }

    @Test
    fun `saveProject calls createProject when no project exists`() = runTest {
        // Mock no current project
        every { projectRepository.currentProject.value } returns null
        
        val uri = mockk<Uri>(relaxed = true)
        viewModel.onAddLayer(uri)
        testDispatcher.scheduler.advanceUntilIdle()
        
        viewModel.saveProject()
        testDispatcher.scheduler.advanceUntilIdle()
        
        coVerify { projectRepository.createProject(any<com.hereliesaz.graffitixr.common.model.GraffitiProject>()) }
    }

    @Test
    fun `onLayerRemoved removes layer and clears active ID if necessary`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
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
        val existingProject = mockk<com.hereliesaz.graffitixr.common.model.GraffitiProject>(relaxed = true)
        every { projectRepository.currentProject.value } returns existingProject
        
        viewModel.saveProject()
        testDispatcher.scheduler.advanceUntilIdle()
        
        coVerify { projectRepository.updateProject(any<com.hereliesaz.graffitixr.common.model.GraffitiProject>()) }
    }
}