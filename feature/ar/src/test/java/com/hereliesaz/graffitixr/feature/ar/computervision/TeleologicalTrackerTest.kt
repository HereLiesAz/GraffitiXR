package com.hereliesaz.graffitixr.feature.ar.computervision

import com.hereliesaz.graffitixr.common.util.ImageProcessingUtils
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.opencv.core.Mat

class TeleologicalTrackerTest {

    private val slamManager: SlamManager = mockk(relaxed = true)
    private lateinit var tracker: TeleologicalTracker

    @Before
    fun setUp() {
        mockkObject(ImageProcessingUtils)
        tracker = TeleologicalTracker(slamManager)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `trackAndCorrect with null PnP result does not call updateAnchorTransform`() {
        every { ImageProcessingUtils.solvePnP(any(), any(), any()) } returns null

        tracker.trackAndCorrect(mockk(), mockk(), FloatArray(4))

        verify(exactly = 0) { slamManager.updateAnchorTransform(any()) }
    }

    @Test
    fun `trackAndCorrect with valid PnP result calls updateAnchorTransform with 16-element array`() {
        val mat = mockk<Mat>(relaxed = true)
        // Mat.get(row, col) returns DoubleArray; supply at least one element to avoid AIOBE
        every { mat.get(any<Int>(), any<Int>()) } returns doubleArrayOf(1.0)
        every { ImageProcessingUtils.solvePnP(any(), any(), any()) } returns mat

        tracker.trackAndCorrect(mockk(), mockk(), FloatArray(4))

        verify { slamManager.updateAnchorTransform(match { it.size == 16 }) }
    }

    @Test
    fun `trackAndCorrect releases Mat after use`() {
        val mat = mockk<Mat>(relaxed = true)
        every { mat.get(any<Int>(), any<Int>()) } returns doubleArrayOf(0.0)
        every { ImageProcessingUtils.solvePnP(any(), any(), any()) } returns mat

        tracker.trackAndCorrect(mockk(), mockk(), FloatArray(4))

        verify { mat.release() }
    }

    // processTeleologicalFrame() returns Mat() directly — that constructor calls native code
    // so it cannot be exercised in a pure JVM unit test. Covered by instrumented tests.
}
