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
        val data = ByteArray(200) { 128.toByte() }
        val buffer = ByteBuffer.wrap(data)

        every { image.planes } returns arrayOf(plane)
        every { plane.buffer } returns buffer

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

        // All 255
        val data = ByteArray(200) { 255.toByte() }
        val buffer = ByteBuffer.wrap(data)

        every { image.planes } returns arrayOf(plane)
        every { plane.buffer } returns buffer

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

        // All 0
        val data = ByteArray(200) { 0.toByte() }
        val buffer = ByteBuffer.wrap(data)

        every { image.planes } returns arrayOf(plane)
        every { plane.buffer } returns buffer

        // Act
        analyzer.analyze(image)

        // Assert
        assertTrue("Result should be 0.0, was $result", result < 0.01f)
        verify { image.close() }
    }
}
