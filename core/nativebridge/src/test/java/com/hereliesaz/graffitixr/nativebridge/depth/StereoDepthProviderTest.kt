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
    fun `processStereoFrames calls slamManager`() {
        val left = ByteArray(10)
        val right = ByteArray(10)
        val w = 640
        val h = 480
        val ts = 12345L

        provider.processStereoFrames(left, right, w, h, ts)

        verify { slamManager.feedStereoData(any(), any(), w, h, ts) }
    }

    @Test
    fun `processStereoFrames does NOT call slamManager when data is empty`() {
        val left = ByteArray(0)
        val right = ByteArray(0)
        val w = 640
        val h = 480
        val ts = 12345L

        provider.processStereoFrames(left, right, w, h, ts)

        verify(exactly = 0) { slamManager.feedStereoData(any(), any(), any(), any(), any()) }
    }
}
