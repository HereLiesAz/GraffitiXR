package com.hereliesaz.graffitixr.common.util

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.Window
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class CaptureTest {

    @Before
    fun setUp() {
        mockkStatic(Bitmap::class)
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        mockkStatic(Looper::class)
        every { Looper.getMainLooper() } returns mockk()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun captureWindow_invalidDimensions_returnsNullCallback() {
        // Arrange
        val activity = mockk<Activity>()
        val window = mockk<Window>()
        val decorView = mockk<View>()

        every { activity.window } returns window
        every { window.decorView } returns decorView

        // Case 1: Width 0
        every { decorView.width } returns 0
        every { decorView.height } returns 100

        var result: Bitmap? = null
        captureWindow(activity) { result = it }

        assertNull("Callback should be called with null for width 0", result)
        // Verify createBitmap was NOT called
        verify(exactly = 0) { Bitmap.createBitmap(any(), any(), any<Bitmap.Config>()) }

        // Case 2: Height 0
        every { decorView.width } returns 100
        every { decorView.height } returns 0

        captureWindow(activity) { result = it }

        assertNull("Callback should be called with null for height 0", result)
        verify(exactly = 0) { Bitmap.createBitmap(any(), any(), any<Bitmap.Config>()) }

        // Case 3: Both 0
        every { decorView.width } returns 0
        every { decorView.height } returns 0

        captureWindow(activity) { result = it }

        assertNull("Callback should be called with null for dimensions 0", result)
        verify(exactly = 0) { Bitmap.createBitmap(any(), any(), any<Bitmap.Config>()) }
    }

    @Test
    fun captureWindow_validDimensions_callsCreateBitmap() {
        // Arrange
        val activity = mockk<Activity>()
        val window = mockk<Window>()
        val decorView = mockk<View>()
        val mockBitmap = mockk<Bitmap>()

        every { activity.window } returns window
        every { window.decorView } returns decorView
        every { decorView.width } returns 1080
        every { decorView.height } returns 1920

        every { Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888) } returns mockBitmap
        every { decorView.getLocationInWindow(any()) } returns Unit

        // Note: PixelCopy is Android SDK dependent and static.
        // In a pure unit test without Robolectric, we can't easily mock PixelCopy unless we wrap it or use Robolectric.
        // However, we just want to verify we PASSED the validation check and attempted to create the bitmap.
        // The previous crash was at createBitmap.

        // We expect an exception or failure later because PixelCopy or Build.VERSION might fail in mock environment,
        // but verify(exactly = 1) { Bitmap.createBitmap(...) } is what validates the fix for the crash.

        // To avoid PixelCopy crashing the test if SDK_INT is mocked/defaulted:
        // Build.VERSION.SDK_INT is static final, hard to mock with MockK without reflection or special setup.
        // However, if we just run it, let's see. If it crashes at PixelCopy, it means we passed createBitmap!

        try {
             captureWindow(activity) { }
        } catch (e: Exception) {
            // Ignore downstream errors (like PixelCopy not mocked)
        }

        verify(exactly = 1) { Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888) }
    }
}
