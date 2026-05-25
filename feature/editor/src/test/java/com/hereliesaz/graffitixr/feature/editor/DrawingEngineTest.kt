package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.util.NativeLibLoader
import com.hereliesaz.graffitixr.feature.editor.util.ImageProcessor
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

class DrawingEngineTest {

    private val slamManager: SlamManager = mockk(relaxed = true)
    private lateinit var engine: DrawingEngine

    @Before
    fun setup() {
        // ImageProcessor's init block calls NativeLibLoader.loadAll() (Android-arm .so) — no-op it.
        mockkObject(NativeLibLoader)
        every { NativeLibLoader.loadAll() } returns Unit
        mockkObject(ImageProcessor)
        every {
            ImageProcessor.mapScreenToBitmap(any(), any(), any(), any(), any(), any(), any(), any())
        } returns emptyList<Offset>()
        engine = DrawingEngine(slamManager)
    }

    @After
    fun tearDown() = unmockkAll()

    private fun bitmap(): Bitmap = mockk(relaxed = true) {
        every { width } returns 10
        every { height } returns 10
        every { copy(any(), any()) } returns this
    }

    private fun stroke(tool: Tool = Tool.NONE) = StrokeCommand(
        path = listOf(Offset.Zero),
        canvasSize = IntSize(10, 10),
        tool = tool,
        brushSize = 5f,
        brushColor = 0,
        intensity = 0.5f,
    )

    @Test
    fun `composite with no strokes returns a copy of the base and applies nothing`() = runTest {
        val base = bitmap()
        val result = engine.composite(base, emptyList())
        assertSame(base, result) // copy() is stubbed to return the same mock
        coVerify(exactly = 0) { ImageProcessor.applyToolToBitmap(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `composite replays each non-liquify stroke via applyToolToBitmap with replaceExisting=true`() = runTest {
        val base = bitmap()
        coEvery {
            ImageProcessor.applyToolToBitmap(any(), any(), any(), any(), any(), any(), any(), any())
        } returns base

        engine.composite(base, listOf(stroke(), stroke()))

        coVerify(exactly = 2) {
            ImageProcessor.applyToolToBitmap(any(), any(), any(), any(), any(), any(), eq(true), any())
        }
    }

    @Test
    fun `composite routes a LIQUIFY stroke through SlamManager`() = runTest {
        val base = bitmap()
        engine.composite(base, listOf(stroke(Tool.LIQUIFY)))

        verify { slamManager.prepareLiquify(any()) }
        verify { slamManager.applyLiquify(any(), any(), any()) }
        verify { slamManager.bakeLiquify(any()) }
    }

    @Test
    fun `applySingleStroke applies the tool with replaceExisting=false`() = runTest {
        val base = bitmap()
        coEvery {
            ImageProcessor.applyToolToBitmap(any(), any(), any(), any(), any(), any(), any(), any())
        } returns base

        engine.applySingleStroke(base, stroke())

        coVerify(exactly = 1) {
            ImageProcessor.applyToolToBitmap(any(), any(), any(), any(), any(), any(), eq(false), any())
        }
    }
}
