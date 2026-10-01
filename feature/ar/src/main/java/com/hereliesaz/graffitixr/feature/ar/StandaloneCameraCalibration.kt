package com.hereliesaz.graffitixr.feature.ar

import com.hereliesaz.graffitixr.common.sensor.CameraIntrinsics

/**
 * Convert full ImageProxy-buffer intrinsics into the coordinate system of CameraX's crop rectangle.
 *
 * Cropping does not change focal length in buffer pixels; it only moves the principal point by the
 * crop origin. Rotation is intentionally a separate step handled by CaptureRotation so pixels and
 * calibration follow the exact same crop-then-rotate order.
 */
internal fun cropCameraIntrinsics(
    intrinsics: CameraIntrinsics,
    cropLeft: Int,
    cropTop: Int,
    cropWidth: Int,
    cropHeight: Int,
): CameraIntrinsics {
    require(cropLeft >= 0 && cropTop >= 0)
    require(cropWidth > 0 && cropHeight > 0)
    require(cropLeft + cropWidth <= intrinsics.width)
    require(cropTop + cropHeight <= intrinsics.height)
    return CameraIntrinsics(
        fx = intrinsics.fx,
        fy = intrinsics.fy,
        cx = intrinsics.cx - cropLeft,
        cy = intrinsics.cy - cropTop,
        width = cropWidth,
        height = cropHeight,
    )
}
