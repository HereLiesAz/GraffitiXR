package com.hereliesaz.graffitixr.utils

import android.app.Activity
import android.graphics.Bitmap
import android.view.View
import android.view.Window
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull

class CaptureTest {

    @Test
    fun captureWindow_withZeroWidth_returnsNullAndDoesNotCreateBitmap() {
        // Arrange
        val activity = mockk<Activity>()
        val window = mockk<Window>()
        val decorView = mockk<View>()

        every { activity.window } returns window
        every { window.decorView } returns decorView
        every { decorView.width } returns 0
        every { decorView.height } returns 100

        mockkStatic(Bitmap::class)
        // Simulate the crash if called with invalid dimensions
        every { Bitmap.createBitmap(0, 100, any<Bitmap.Config>()) } throws IllegalArgumentException("width and height must be > 0")

        var callbackCalled = false
        var callbackResult: Bitmap? = null // Initialize to non-null if we want to be strict, but null is fine

        // Act
        try {
            captureWindow(activity) { bitmap ->
                callbackCalled = true
                callbackResult = bitmap
            }
        } catch (e: IllegalArgumentException) {
            // Expected failure before fix
            println("Caught expected crash: ${e.message}")
            throw e
        }

        // Assert
        assertTrue("Callback should be called", callbackCalled)
        assertNull("Bitmap should be null", callbackResult)

        verify(exactly = 0) { Bitmap.createBitmap(any<Int>(), any<Int>(), any<Bitmap.Config>()) }
    }
}
