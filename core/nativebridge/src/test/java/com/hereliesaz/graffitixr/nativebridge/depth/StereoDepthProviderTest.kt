package com.hereliesaz.graffitixr.nativebridge.depth

import android.content.Context
import android.media.Image
import com.hereliesaz.graffitixr.common.util.CameraCapabilities
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StereoDepthProviderTest {

    private val context: Context = mockk()
    private val slamManager: SlamManager = mockk(relaxed = true)
    private lateinit var provider: StereoDepthProvider

    @Before
    fun setup() {
        provider = StereoDepthProvider(context, slamManager)
        mockkObject(CameraCapabilities)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `isSupported returns true when CameraCapabilities says so`() {
        every { CameraCapabilities.hasLogicalMultiCameraSupport(context) } returns true
        assertTrue(provider.isSupported())
    }

    @Test
    fun `isSupported returns false when CameraCapabilities says so`() {
        every { CameraCapabilities.hasLogicalMultiCameraSupport(context) } returns false
        assertFalse(provider.isSupported())
    }

    @Test
    fun `processStereoPair calls slamManager when supported`() {
        every { CameraCapabilities.hasLogicalMultiCameraSupport(context) } returns true
        val leftImage = mockk<Image>()
        val rightImage = mockk<Image>()

        provider.processStereoPair(leftImage, rightImage)

        verify { slamManager.feedStereoData(leftImage, rightImage) }
    }

    @Test
    fun `processStereoPair does NOT call slamManager when NOT supported`() {
        every { CameraCapabilities.hasLogicalMultiCameraSupport(context) } returns false
        val leftImage = mockk<Image>()
        val rightImage = mockk<Image>()

        provider.processStereoPair(leftImage, rightImage)

        verify(exactly = 0) { slamManager.feedStereoData(any(), any()) }
    }
}
