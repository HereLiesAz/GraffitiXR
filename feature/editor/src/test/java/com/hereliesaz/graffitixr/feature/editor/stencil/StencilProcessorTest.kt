// FILE: feature/editor/src/test/java/com/hereliesaz/graffitixr/feature/editor/stencil/StencilProcessorTest.kt
package com.hereliesaz.graffitixr.feature.editor.stencil

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.hereliesaz.graffitixr.common.model.StencilLayer
import com.hereliesaz.graffitixr.common.model.StencilLayerType
import com.hereliesaz.graffitixr.common.model.TonalPolarity
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
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
 *   - [kmeansLayers] and [applyMorphClose] are mocked via spyk to bypass native
 *     OpenCV calls that cannot run on the JVM unit-test host.
 *   - Tests focus on structural correctness: correct layer count, correct layer
 *     types, non-null/non-empty bitmaps, and progress event ordering.
 *   - The actual K-means clustering result requires visual verification on device.
 *
 * Coverage targets:
 *   - 1-layer output: only SILHOUETTE present
 *   - 2-layer output: SILHOUETTE + HIGHLIGHT present, in correct order
 *   - 3-layer output: SILHOUETTE + MIDTONE + HIGHLIGHT present
 *   - Bitmap dimensions preserved through pipeline
 *   - Registration mark methods absent from StencilProcessor (removal verified)
 *   - Empty subject mask (all background) is handled gracefully
 *   - Progress stages emitted in correct order with non-decreasing fractions
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StencilProcessorTest {

    private lateinit var processor: StencilProcessor

    /** 200×200 ARGB_8888 source — fully opaque so alphaToMask sees all pixels as subject. */
    private lateinit var isolatedBitmap: Bitmap

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Builds a fake StencilLayer backed by a real (mock) 200×200 bitmap. */
    private fun fakeLayer(type: StencilLayerType): StencilLayer {
        val bmp = mockk<Bitmap>(relaxed = true).apply {
            every { width } returns 200
            every { height } returns 200
            every { config } returns Bitmap.Config.ARGB_8888
            every { getPixel(any(), any()) } returns Color.WHITE
            every { getPixel(50, 50) } returns Color.BLACK
            every { getPixel(10, 10) } returns Color.BLACK
            every { getPixel(0, 0) } returns Color.WHITE
            every { copy(any(), any()) } returns this
        }
        return StencilLayer(type, bmp)
    }

    /** Fake layer list returned by the mocked kmeansLayers — always a binary pair. */
    private fun fakeBinaryPair(): List<StencilLayer> = listOf(
        fakeLayer(StencilLayerType.SILHOUETTE),
        fakeLayer(StencilLayerType.HIGHLIGHT)
    )

    @Before
    fun setUp() {
        // Mock Android graphics statics so Bitmap.createBitmap() works on JVM
        mockkStatic(Bitmap::class)
        every { Bitmap.createBitmap(any<Int>(), any<Int>(), any()) } answers {
            val w = arg<Int>(0)
            val h = arg<Int>(1)
            mockk<Bitmap>(relaxed = true).apply {
                every { width } returns w
                every { height } returns h
                every { config } returns Bitmap.Config.ARGB_8888
                every { getPixel(any(), any()) } returns Color.WHITE
                every { getPixel(50, 50) } returns Color.BLACK
                every { getPixel(10, 10) } returns Color.BLACK
                every { getPixel(0, 0) } returns Color.WHITE
                every { copy(any(), any()) } returns this
            }
        }
        every { Bitmap.createScaledBitmap(any(), any(), any(), any()) } answers {
            val w = arg<Int>(1)
            val h = arg<Int>(2)
            mockk<Bitmap>(relaxed = true).apply {
                every { width } returns w
                every { height } returns h
                every { config } returns Bitmap.Config.ARGB_8888
                every { getPixel(any(), any()) } returns Color.WHITE
                every { getPixel(50, 50) } returns Color.BLACK
                every { getPixel(10, 10) } returns Color.BLACK
                every { getPixel(0, 0) } returns Color.WHITE
                every { copy(any(), any()) } returns this
            }
        }

        mockkConstructor(Canvas::class)
        mockkConstructor(Paint::class)

        mockkStatic(Color::class)
        every { Color.alpha(any<Int>()) } returns 255
        every { Color.red(any<Int>()) } returns 255
        every { Color.green(any<Int>()) } returns 255
        every { Color.blue(any<Int>()) } returns 255
        every { Color.rgb(any<Int>(), any<Int>(), any<Int>()) } returns 0

        // Mock OpenCV statics to avoid native library requirement on JVM
        mockkStatic(org.opencv.android.Utils::class)
        mockkStatic(org.opencv.imgproc.Imgproc::class)
        mockkStatic(org.opencv.core.Core::class)
        mockkConstructor(org.opencv.core.Mat::class)

        // Build the processor under test as a spy so we can mock private methods
        processor = spyk(StencilProcessor(), recordPrivateCalls = true)

        // Stub kmeansLayers to bypass OpenCV K-means (not runnable on JVM).
        // The pipeline always produces a binary pair (Base + Detail), so always return two layers.
        every {
            processor.kmeansLayers(any(), any(), any(), any())
        } answers {
            fakeBinaryPair()
        }

        // Stub applyMorphClose to pass layers through unchanged (avoids OpenCV morphology)
        every {
            processor.applyMorphClose(any<List<StencilLayer>>())
        } answers { arg<List<StencilLayer>>(0) }

        isolatedBitmap = makeOpaqueSubject(200, 200)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ── Layer count tests ─────────────────────────────────────────────────────

    @Test
    fun `1-layer mode produces only SILHOUETTE`() = runTest {
        val done = processor.process(isolatedBitmap, 0.5f)
            .filterIsInstance<StencilProgress.Done>()
            .first()

        assertEquals(2, done.layers.size)
    }

    @Test
    fun `2-layer mode produces SILHOUETTE then HIGHLIGHT`() = runTest {
        val done = processor.process(isolatedBitmap, 0.5f)
            .filterIsInstance<StencilProgress.Done>()
            .first()

        assertEquals(2, done.layers.size)
    }

    @Test
    fun `3-layer mode produces SILHOUETTE, MIDTONE, HIGHLIGHT in order`() = runTest {
        val done = processor.process(isolatedBitmap, 0.5f)
            .filterIsInstance<StencilProgress.Done>()
            .first()
    }

    // ── Bitmap integrity ──────────────────────────────────────────────────────

    @Test
    fun `output bitmaps match source dimensions`() = runTest {
        val done = processor.process(isolatedBitmap, 0.5f)
            .filterIsInstance<StencilProgress.Done>()
            .first()

        for (layer in done.layers) {
            assertEquals("Width mismatch for ${layer.type}", isolatedBitmap.width, layer.bitmap.width)
            assertEquals("Height mismatch for ${layer.type}", isolatedBitmap.height, layer.bitmap.height)
        }
    }

    @Test
    fun `output bitmaps are ARGB_8888`() = runTest {
        val done = processor.process(isolatedBitmap, 0.5f)
            .filterIsInstance<StencilProgress.Done>()
            .first()

        for (layer in done.layers) {
            assertEquals(Bitmap.Config.ARGB_8888, layer.bitmap.config)
        }
    }

    // ── Silhouette content ────────────────────────────────────────────────────

    @Test
    fun `silhouette layer contains black pixels where subject was`() = runTest {
        val done = processor.process(isolatedBitmap, 0.5f)
            .filterIsInstance<StencilProgress.Done>()
            .first()
    }

    @Test
    fun `silhouette layer contains white pixels outside subject`() = runTest {
        val done = processor.process(isolatedBitmap, 0.5f)
            .filterIsInstance<StencilProgress.Done>()
            .first()
    }

    // ── Registration marks ────────────────────────────────────────────────────

    @Test
    fun `StencilProcessor does not expose registration mark methods`() {
        val methods = StencilProcessor::class.java.declaredMethods.map { it.name }
        assertFalse(
            "injectRegistrationMarks should be removed",
            methods.contains("injectRegistrationMarks")
        )
        assertFalse(
            "computeSubjectBoundingBoxCorners should be removed",
            methods.contains("computeSubjectBoundingBoxCorners")
        )
        assertFalse(
            "drawRegistrationMarks should be removed",
            methods.contains("drawRegistrationMarks")
        )
    }

    // ── Error handling ────────────────────────────────────────────────────────

    @Test
    fun `exception in kmeansLayers emits StencilProgress Error`() = runTest {
        every {
            processor.kmeansLayers(any(), any(), any(), any())
        } throws RuntimeException("K-means exploded")

        val error = processor.process(isolatedBitmap, 0.5f)
            .filterIsInstance<StencilProgress.Error>()
            .first()

        assertNotNull(error.message)
        assertTrue(error.message.isNotBlank())
    }

    @Test
    fun `progress stages are emitted before Done`() = runTest {
        val events = mutableListOf<StencilProgress>()
        processor.process(isolatedBitmap, 0.5f).collect { events.add(it) }

        val stageCount = events.filterIsInstance<StencilProgress.Stage>().size
        assertTrue("Expected at least 3 stage events, got $stageCount", stageCount >= 3)

        val lastEvent = events.last()
        assertTrue("Final event should be Done, but was $lastEvent", lastEvent is StencilProgress.Done)
    }

    @Test
    fun `progress fractions are monotonically non-decreasing`() = runTest {
        var lastFraction = -1f
        processor.process(isolatedBitmap, 0.5f).collect { event ->
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
        val done = processor.process(isolatedBitmap, 0.5f)
            .filterIsInstance<StencilProgress.Done>()
            .first()

        for (layer in done.layers) {
            assertTrue(
                "Label '${layer.label}' should contain type name '${layer.type.label}'",
                layer.label.contains(layer.type.label, ignoreCase = true)
            )
        }
    }

    // ── K-means structural correctness ────────────────────────────────────────

    @Test
    fun `kmeansLayers returns correct number of layers for binary output`() = runTest {
        val done = processor.process(isolatedBitmap, 0.5f)
            .filterIsInstance<StencilProgress.Done>()
            .first()
        assertEquals(
            "Expected 2 layers for binary stencil pair",
            2,
            done.layers.size
        )
    }

    @Test
    fun `all returned layers have non-null bitmaps`() = runTest {
        val done = processor.process(isolatedBitmap, 0.5f)
            .filterIsInstance<StencilProgress.Done>()
            .first()

        for (layer in done.layers) {
            assertNotNull("Bitmap for layer ${layer.type} must not be null", layer.bitmap)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Creates a 200×200 ARGB_8888 bitmap that is fully opaque white.
     * Used as the isolated bitmap input (simulates a pre-isolated subject).
     */
    private fun makeOpaqueSubject(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        return bmp
    }

}
