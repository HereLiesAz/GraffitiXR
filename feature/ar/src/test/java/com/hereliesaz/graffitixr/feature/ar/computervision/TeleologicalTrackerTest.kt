// FILE: feature/ar/src/test/java/com/hereliesaz/graffitixr/feature/ar/computervision/TeleologicalTrackerTest.kt
package com.hereliesaz.graffitixr.feature.ar.computervision

import com.hereliesaz.graffitixr.common.model.Fingerprint
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Test
import org.opencv.core.KeyPoint

class TeleologicalTrackerTest {

    private val slamManager: SlamManager = mockk(relaxed = true)
    private lateinit var tracker: TeleologicalTracker

    @Before
    fun setUp() {
        tracker = TeleologicalTracker(slamManager)
    }

    @Test
    fun `registerTarget correctly passes fingerprint data to SlamManager`() {
        val mockDescriptors = ByteArray(10) { it.toByte() }
        val mockPoints3d = listOf(1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f)

        val fingerprint = Fingerprint(
            keypoints = listOf(KeyPoint(0f, 0f, 1f)),
            points3d = mockPoints3d,
            descriptorsData = mockDescriptors,
            descriptorsRows = 2,
            descriptorsCols = 5,
            descriptorsType = 0
        )

        tracker.registerTarget(fingerprint)

        verify {
            slamManager.setTargetFingerprint(
                mockDescriptors,
                2,
                5,
                0,
                match { it.contentEquals(mockPoints3d.toFloatArray()) }
            )
        }
    }

    @Test
    fun `forceAnchorTransform calls updateAnchorTransform on SlamManager`() {
        val mockTransform = FloatArray(16) { it.toFloat() }

        tracker.forceAnchorTransform(mockTransform)

        verify { slamManager.updateAnchorTransform(mockTransform) }
    }
}