package com.hereliesaz.graffitixr.nativebridge.depth

import com.hereliesaz.graffitixr.nativebridge.SlamManager
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Test

class StereoDepthProviderTest {

    private val slamManager: SlamManager = mockk(relaxed = true)
    private lateinit var provider: StereoDepthProvider

    @Before
    fun setup() {
        provider = StereoDepthProvider(slamManager)
    }

    @Test
    fun `processFrames calls slamManager`() {
        val left = ByteArray(10)
        val right = ByteArray(10)
        val w = 640
        val h = 480

        provider.processFrames(left, right, w, h)

        verify { slamManager.feedStereoData(left, right, w, h) }
    }

    @Test
    fun `processFrames does NOT call slamManager when data is empty`() {
        val left = ByteArray(0)
        val right = ByteArray(0)
        val w = 640
        val h = 480

        provider.processFrames(left, right, w, h)

        verify(exactly = 0) { slamManager.feedStereoData(any(), any(), any(), any()) }
    }
}
