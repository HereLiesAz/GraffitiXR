package com.hereliesaz.graffitixr.common.util

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCapabilitiesTest {

    @Test
    fun `hasLogicalMultiCameraSupport returns true when supported`() {
        val context = mockk<Context>()
        val manager = mockk<CameraManager>()
        val characteristics = mockk<CameraCharacteristics>()

        every { context.getSystemService(Context.CAMERA_SERVICE) } returns manager
        every { manager.cameraIdList } returns arrayOf("0")
        every { manager.getCameraCharacteristics("0") } returns characteristics
        every { characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) } returns intArrayOf(
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
        )

        assertTrue(CameraCapabilities.hasLogicalMultiCameraSupport(context))
    }

    @Test
    fun `hasLogicalMultiCameraSupport returns false when capabilities are null`() {
        val context = mockk<Context>()
        val manager = mockk<CameraManager>()
        val characteristics = mockk<CameraCharacteristics>()

        every { context.getSystemService(Context.CAMERA_SERVICE) } returns manager
        every { manager.cameraIdList } returns arrayOf("0")
        every { manager.getCameraCharacteristics("0") } returns characteristics
        every { characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) } returns null

        assertFalse(CameraCapabilities.hasLogicalMultiCameraSupport(context))
    }

    @Test
    fun `hasLogicalMultiCameraSupport returns false when exception occurs`() {
        val context = mockk<Context>()
        val manager = mockk<CameraManager>()

        every { context.getSystemService(Context.CAMERA_SERVICE) } throws RuntimeException("Camera service not available")

        assertFalse(CameraCapabilities.hasLogicalMultiCameraSupport(context))
    }
}
