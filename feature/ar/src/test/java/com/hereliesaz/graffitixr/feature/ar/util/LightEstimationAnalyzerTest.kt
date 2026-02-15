package com.hereliesaz.graffitixr.feature.ar.util

import androidx.camera.core.ImageProxy
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class LightEstimationAnalyzerTest {

    @Test
    fun `analyze calculates correct average luminosity`() {
        // Arrange
        var result = -1f
        val analyzer = LightEstimationAnalyzer { result = it }

        val image = mockk<ImageProxy>(relaxed = true)
        val plane = mockk<ImageProxy.PlaneProxy>()

        // Create a buffer with known values (e.g. all 128 = ~0.5 intensity)
        // 128 is 0x80.
        // Assuming width=10, height=20, stride=1
        val width = 10
        val height = 20
        val data = ByteArray(width * height) { 128.toByte() }
        val buffer = ByteBuffer.wrap(data)

        every { image.planes } returns arrayOf(plane)
        every { image.width } returns width
        every { image.height } returns height
        every { plane.buffer } returns buffer
        every { plane.pixelStride } returns 1
        every { plane.rowStride } returns width // Tight packing

        // Act
        analyzer.analyze(image)

        // Assert
        // 128 / 255.0 = 0.50196...
        assertTrue("Result should be around 0.5, was $result", result > 0.5f && result < 0.51f)
        verify { image.close() }
    }

    @Test
    fun `analyze handles full white image`() {
        // Arrange
        var result = -1f
        val analyzer = LightEstimationAnalyzer { result = it }

        val image = mockk<ImageProxy>(relaxed = true)
        val plane = mockk<ImageProxy.PlaneProxy>()

        val width = 10
        val height = 20
        // All 255
        val data = ByteArray(width * height) { 255.toByte() }
        val buffer = ByteBuffer.wrap(data)

        every { image.planes } returns arrayOf(plane)
        every { image.width } returns width
        every { image.height } returns height
        every { plane.buffer } returns buffer
        every { plane.pixelStride } returns 1
        every { plane.rowStride } returns width

        // Act
        analyzer.analyze(image)

        // Assert
        assertTrue("Result should be 1.0, was $result", result > 0.99f)
        verify { image.close() }
    }

    @Test
    fun `analyze handles full black image`() {
        // Arrange
        var result = -1f
        val analyzer = LightEstimationAnalyzer { result = it }

        val image = mockk<ImageProxy>(relaxed = true)
        val plane = mockk<ImageProxy.PlaneProxy>()

        val width = 10
        val height = 20
        // All 0
        val data = ByteArray(width * height) { 0.toByte() }
        val buffer = ByteBuffer.wrap(data)

        every { image.planes } returns arrayOf(plane)
        every { image.width } returns width
        every { image.height } returns height
        every { plane.buffer } returns buffer
        every { plane.pixelStride } returns 1
        every { plane.rowStride } returns width

        // Act
        analyzer.analyze(image)

        // Assert
        assertTrue("Result should be 0.0, was $result", result < 0.01f)
        verify { image.close() }
    }
}
