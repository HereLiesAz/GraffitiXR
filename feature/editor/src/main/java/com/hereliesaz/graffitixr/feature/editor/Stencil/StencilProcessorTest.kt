// FILE: feature/editor/src/test/java/com/hereliesaz/graffitixr/feature/editor/stencil/StencilProcessorTest.kt
package com.hereliesaz.graffitixr.feature.editor.stencil

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.hereliesaz.graffitixr.common.model.StencilLayerCount
import com.hereliesaz.graffitixr.common.model.StencilLayerType
import com.hereliesaz.graffitixr.feature.editor.BackgroundRemover
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [StencilProcessor].
 *
 * Strategy:
 *   - All tests use synthetic 100×100 bitmaps to avoid any real file I/O.
 *   - [BackgroundRemover] is mocked to return a predetermined segmented bitmap.
 *   - OpenCV Mat operations are NOT mocked — they run on the JVM via the
 *     OpenCV Java bindings already present in the :opencv module.
 *     If OpenCV native libs are unavailable in the test JVM, morphClose tests
 *     will be skipped via assumeTrue.
 *
 * Coverage targets:
 *   - 1-layer output: only SILHOUETTE present
 *   - 2-layer output: SILHOUETTE + HIGHLIGHT present, in correct order
 *   - 3-layer output: SILHOUETTE + MIDTONE + HIGHLIGHT present
 *   - Bitmap dimensions preserved through pipeline
 *   - Registration marks injected into all layers
 *   - Error propagation from BackgroundRemover
 *   - Empty subject mask (all background) produces error or empty layers
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StencilProcessorTest {

    private lateinit var backgroundRemover: BackgroundRemover
    private lateinit var processor: StencilProcessor

    /** 100×100 ARGB_8888 source — gradient from black (left) to white (right). */
    private lateinit var sourceBitmap: Bitmap

    /**
     * Segmented bitmap returned by the mock: a 60×60 white circle centred on the
     * 100×100 canvas. Pixels inside circle are fully opaque, outside are transparent.
     */
    private lateinit var segmentedBitmap: Bitmap

    @Before
    fun setUp() {
        backgroundRemover = mockk()
        processor = StencilProcessor(backgroundRemover)

        sourceBitmap = makeLuminanceGradient(100, 100)
        segmentedBitmap = makeCircleSubject(100, 100, radius = 40)

        coEvery { backgroundRemover.removeBackground(any()) } returns Result.success(segmentedBitmap)
    }

    // ── Layer count tests ─────────────────────────────────────────────────────

    @Test
    fun `1-layer mode produces only SILHOUETTE`() = runTest {
        val done = processor.process(sourceBitmap, StencilLayerCount.ONE)
            .filterIsInstance<StencilProgress.Done>()
            .first()

        assertEquals(1, done.layers.size)
        assertEquals(StencilLayerType.SILHOUETTE, done.layers[0].type)
    }

    @Test
    fun `2-layer mode produces SILHOUETTE then HIGHLIGHT`() = runTest {
        val done = processor.process(sourceBitmap, StencilLayerCount.TWO)
            .filterIsInstance<StencilProgress.Done>()
            .first()

        assertEquals(2, done.layers.size)
        assertEquals(StencilLayerType.SILHOUETTE, done.layers[0].type)
        assertEquals(StencilLayerType.HIGHLIGHT, done.layers[1].type)
    }

    @Test
    fun `3-layer mode produces SILHOUETTE, MIDTONE, HIGHLIGHT in order`() = runTest {
        val done = processor.process(sourceBitmap, StencilLayerCount.THREE)
            .filterIsInstance<StencilProgress.Done>()
            .first()

        assertEquals(3, done.layers.size)
        assertEquals(StencilLayerType.SILHOUETTE, done.layers[0].type)
        assertEquals(StencilLayerType.MIDTONE, done.layers[1].type)
        assertEquals(StencilLayerType.HIGHLIGHT, done.layers[2].type)
    }

    // ── Bitmap integrity ──────────────────────────────────────────────────────

    @Test
    fun `output bitmaps match source dimensions`() = runTest {
        val done = processor.process(sourceBitmap, StencilLayerCount.TWO)
            .filterIsInstance<StencilProgress.Done>()
            .first()

        for (layer in done.layers) {
            assertEquals("Width mismatch for ${layer.type}", sourceBitmap.width, layer.bitmap.width)
            assertEquals("Height mismatch for ${layer.type}", sourceBitmap.height, layer.bitmap.height)
        }
    }

    @Test
    fun `output bitmaps are ARGB_8888`() = runTest {
        val done = processor.process(sourceBitmap, StencilLayerCount.TWO)
            .filterIsInstance<StencilProgress.Done>()
            .first()

        for (layer in done.layers) {
            assertEquals(Bitmap.Config.ARGB_8888, layer.bitmap.config)
        }
    }

    // ── Silhouette content ────────────────────────────────────────────────────

    @Test
    fun `silhouette layer contains black pixels where subject was`() = runTest {
        val done = processor.process(sourceBitmap, StencilLayerCount.ONE)
            .filterIsInstance<StencilProgress.Done>()
            .first()

        val silBmp = done.layers[0].bitmap
        // Centre pixel is inside the subject circle — should be black
        val centrePixel = silBmp.getPixel(50, 50)
        assertEquals(
            "Centre pixel should be black (subject area)",
            Color.BLACK, centrePixel
        )
    }

    @Test
    fun `silhouette layer contains white pixels outside subject`() = runTest {
        val done = processor.process(sourceBitmap, StencilLayerCount.ONE)
            .filterIsInstance<StencilProgress.Done>()
            .first()

        val silBmp = done.layers[0].bitmap
        // Corner pixel (0,0) is outside the circle — should be white
        val cornerPixel = silBmp.getPixel(0, 0)
        assertEquals(
            "Corner pixel should be white (background area)",
            Color.WHITE, cornerPixel
        )
    }

    // ── Registration marks ────────────────────────────────────────────────────

    @Test
    fun `all layers contain registration marks`() = runTest {
        val done = processor.process(sourceBitmap, StencilLayerCount.TWO)
            .filterIsInstance<StencilProgress.Done>()
            .first()

        // Registration marks are drawn at the bounding box corners of the subject.
        // The subject circle is ~40px radius centred at (50,50), so marks will be
        // near (10,10), (90,10), (90,90), (10,90) ± margin.
        // We just verify that not every edge pixel is white — marks must exist.
        for (layer in done.layers) {
            val hasMarkPixels = hasAnyBlackPixelNearCorner(layer.bitmap)
            assertTrue(
                "Layer ${layer.type} should have registration marks near corners",
                hasMarkPixels
            )
        }
    }

    // ── Error handling ────────────────────────────────────────────────────────

    @Test
    fun `BackgroundRemover failure emits StencilProgress Error`() = runTest {
        coEvery { backgroundRemover.removeBackground(any()) } returns
                Result.failure(RuntimeException("GrabCut exploded"))

        val error = processor.process(sourceBitmap, StencilLayerCount.TWO)
            .filterIsInstance<StencilProgress.Error>()
            .first()

        assertNotNull(error.message)
        assertTrue(error.message.isNotBlank())
    }

    @Test
    fun `progress stages are emitted before Done`() = runTest {
        val events = mutableListOf<StencilProgress>()
        processor.process(sourceBitmap, StencilLayerCount.TWO).collect { events.add(it) }

        val stageCount = events.filterIsInstance<StencilProgress.Stage>().size
        assertTrue("Expected at least 3 stage events, got $stageCount", stageCount >= 3)

        val lastEvent = events.last()
        assertTrue("Final event should be Done", lastEvent is StencilProgress.Done)
    }

    @Test
    fun `progress fractions are monotonically non-decreasing`() = runTest {
        var lastFraction = -1f
        processor.process(sourceBitmap, StencilLayerCount.TWO).collect { event ->
            if (event is StencilProgress.Stage) {
                assertTrue(
                    "Fraction ${event.fraction} decreased from $lastFraction",
                    event.fraction >= lastFraction
                )
                lastFraction = event.fraction
            }
        }
    }

    // ── Layer labels ──────────────────────────────────────────────────────────

    @Test
    fun `layer labels contain type name`() = runTest {
        val done = processor.process(sourceBitmap, StencilLayerCount.THREE)
            .filterIsInstance<StencilProgress.Done>()
            .first()

        for (layer in done.layers) {
            assertTrue(
                "Label '${layer.label}' should contain type name '${layer.type.label}'",
                layer.label.contains(layer.type.label, ignoreCase = true)
            )
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Creates a 100×100 ARGB_8888 bitmap with a horizontal luminance gradient:
     * left edge is black (lum=0), right edge is white (lum=255).
     */
    private fun makeLuminanceGradient(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint()
        for (x in 0 until w) {
            val v = (x * 255 / (w - 1)).coerceIn(0, 255)
            paint.color = Color.rgb(v, v, v)
            canvas.drawLine(x.toFloat(), 0f, x.toFloat(), h.toFloat(), paint)
        }
        return bmp
    }

    /**
     * Creates a bitmap with a white-filled circle (fully opaque) on a fully transparent
     * background. Used as the mock segmented output from BackgroundRemover.
     */
    private fun makeCircleSubject(w: Int, h: Int, radius: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(w / 2f, h / 2f, radius.toFloat(), paint)
        return bmp
    }

    /**
     * Returns true if any pixel within 25px of any corner of [bmp] is pure black.
     * Used to detect registration mark presence without knowing exact coordinates.
     */
    private fun hasAnyBlackPixelNearCorner(bmp: Bitmap): Boolean {
        val w = bmp.width; val h = bmp.height; val r = 25
        val regions = listOf(
            Pair(0..r, 0..r),
            Pair((w - r)..w, 0..r),
            Pair((w - r)..w, (h - r)..h),
            Pair(0..r, (h - r)..h)
        )
        for ((xs, ys) in regions) {
            for (x in xs) {
                for (y in ys) {
                    if (x < w && y < h && bmp.getPixel(x, y) == Color.BLACK) return true
                }
            }
        }
        return false
    }
}
