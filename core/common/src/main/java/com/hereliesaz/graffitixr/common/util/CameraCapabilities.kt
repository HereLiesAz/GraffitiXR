package com.hereliesaz.graffitixr.common.util

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata

object CameraCapabilities {

    /**
     * Checks if the device supports logical multi-camera capabilities.
     *
     * @param context The application context.
     * @return true if at least one camera supports logical multi-camera, false otherwise.
     */
    fun hasLogicalMultiCameraSupport(context: Context): Boolean {
        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return false
            for (cameraId in manager.cameraIdList) {
                val chars = manager.getCameraCharacteristics(cameraId)
                val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                if (capabilities != null && capabilities.contains(
                        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
                    )
                ) {
                    return true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }
}
