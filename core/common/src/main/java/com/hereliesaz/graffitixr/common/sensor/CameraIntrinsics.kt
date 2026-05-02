package com.hereliesaz.graffitixr.common.sensor

data class CameraIntrinsics(
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    val width: Int,
    val height: Int,
) {
    companion object {
        val UNKNOWN: CameraIntrinsics = CameraIntrinsics(
            fx = 0f, fy = 0f, cx = 0f, cy = 0f, width = 0, height = 0,
        )
    }
}
