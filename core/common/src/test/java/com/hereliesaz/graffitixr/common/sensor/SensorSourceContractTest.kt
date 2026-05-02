package com.hereliesaz.graffitixr.common.sensor

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorSourceContractTest {

    private class Empty : SensorSource

    @Test
    fun `default cameraFrames is empty flow`() = runTest {
        val source = Empty()
        val frames = source.cameraFrames.toList()
        assertTrue(frames.isEmpty())
    }

    @Test
    fun `default imuSamples is empty flow`() = runTest {
        val source = Empty()
        val samples = source.imuSamples.toList()
        assertTrue(samples.isEmpty())
    }

    @Test
    fun `default cameraIntrinsics is UNKNOWN`() {
        val source: SensorSource = Empty()
        assertEquals(CameraIntrinsics.UNKNOWN, source.cameraIntrinsics)
    }

    @Test
    fun `PhoneSensorSource starts with UNKNOWN intrinsics`() {
        val source = PhoneSensorSource()
        assertEquals(CameraIntrinsics.UNKNOWN, source.cameraIntrinsics)
    }
}
