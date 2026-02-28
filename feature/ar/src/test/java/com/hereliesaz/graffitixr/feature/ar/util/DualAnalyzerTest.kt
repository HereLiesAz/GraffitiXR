package com.hereliesaz.graffitixr.feature.ar.util

import android.graphics.ImageFormat
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer

class DualAnalyzerTest {

    private var slamCallCount = 0
    private var lightCallCount = 0
    private var teleologicalCallCount = 0

    private lateinit var analyzer: DualAnalyzer

    @Before
    fun setUp() {
        slamCallCount = 0
        lightCallCount = 0
        teleologicalCallCount = 0
        analyzer = DualAnalyzer(
            onLightUpdate = { lightCallCount++ },
            onTeleologicalFrame = { teleologicalCallCount++ },
            onSlamFrame = { _, _, _ -> slamCallCount++ }
        )
    }

    private fun mockImageProxy(
        format: Int = ImageFormat.YUV_420_888,
        width: Int = 640,
        height: Int = 480
    ): ImageProxy {
        val buffer = ByteBuffer.allocate(width * height)
        val plane = mockk<ImageProxy.PlaneProxy> {
            every { this@mockk.buffer } returns buffer
            every { pixelStride } returns 1
            every { rowStride } returns width
        }
        val imageInfo = mockk<ImageInfo> {
            every { rotationDegrees } returns 0
        }
        return mockk {
            every { planes } returns arrayOf(plane)
            every { this@mockk.format } returns format
            every { this@mockk.width } returns width
            every { this@mockk.height } returns height
            every { this@mockk.imageInfo } returns imageInfo
            every { close() } just runs
        }
    }

    @Test
    fun `SLAM callback is invoked on every frame`() {
        val image = mockImageProxy()
        analyzer.analyze(image)
        analyzer.analyze(image)
        analyzer.analyze(image)
        assertEquals(3, slamCallCount)
    }

    @Test
    fun `SLAM callback receives correct image dimensions`() {
        var capturedWidth = 0
        var capturedHeight = 0
        val a = DualAnalyzer(
            onLightUpdate = {},
            onTeleologicalFrame = {},
            onSlamFrame = { _, w, h -> capturedWidth = w; capturedHeight = h }
        )
        a.analyze(mockImageProxy(width = 1280, height = 720))
        assertEquals(1280, capturedWidth)
        assertEquals(720, capturedHeight)
    }

    @Test
    fun `light callback is called on first frame`() {
        analyzer.analyze(mockImageProxy())
        assertEquals(1, lightCallCount)
    }

    @Test
    fun `light callback is throttled during rapid successive calls`() {
        // All calls within the same millisecond — 200ms throttle should prevent repeat firing
        repeat(10) { analyzer.analyze(mockImageProxy()) }
        assertTrue(
            "Expected light callback called once but was $lightCallCount",
            lightCallCount == 1
        )
    }

    @Test
    fun `teleological callback not triggered for unsupported image format`() {
        // RAW10 is not YUV_420_888 or JPEG — should never reach bitmap extraction
        val image = mockImageProxy(format = ImageFormat.RAW10)
        analyzer.analyze(image)
        assertEquals(0, teleologicalCallCount)
    }

    @Test
    fun `analyzer completes without crash when onSlamFrame is null`() {
        val a = DualAnalyzer(onLightUpdate = {}, onTeleologicalFrame = {})
        a.analyze(mockImageProxy()) // must not throw
    }

    @Test
    fun `light estimation reads average luminosity from Y plane`() {
        // All pixels set to 128 → average ≈ 0.502
        var capturedLight = -1f
        val a = DualAnalyzer(onLightUpdate = { capturedLight = it }, onTeleologicalFrame = {})
        val data = ByteArray(640 * 480) { 128.toByte() }
        val buf = ByteBuffer.wrap(data)
        val plane = mockk<ImageProxy.PlaneProxy> {
            every { buffer } returns buf
            every { pixelStride } returns 1
            every { rowStride } returns 640
        }
        val image = mockk<ImageProxy> {
            every { planes } returns arrayOf(plane)
            every { format } returns ImageFormat.YUV_420_888
            every { width } returns 640
            every { height } returns 480
            every { imageInfo } returns mockk { every { rotationDegrees } returns 0 }
            every { close() } just runs
        }
        a.analyze(image)
        assertTrue("Expected ~0.5, got $capturedLight", capturedLight in 0.5f..0.51f)
    }
}
