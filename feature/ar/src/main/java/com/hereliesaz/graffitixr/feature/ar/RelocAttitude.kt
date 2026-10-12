package com.hereliesaz.graffitixr.feature.ar

/**
 * The camera attitude MobileGS's relocalizer turns a lost frame by (`SlamManager.setCameraAttitude`):
 * earth (East-North-Up) ← OpenCV camera, for the frame as it is fed.
 */
internal object RelocAttitude {

    /** Samples older than this describe another frame: none rather than a wrong one. */
    const val MAX_AGE_NS = 250_000_000L

    /**
     * Row-major earth ← OpenCV camera from a north-referenced body → East-North-Up [quaternion]
     * (`[x, y, z, w]`, `TYPE_ROTATION_VECTOR`) and the frame's GL camera ← device rotation
     * [glCameraFromDevice] (row-major, SphereSLAM `DeviceCameraRotation`). OpenCV's camera is GL's
     * with y and z negated (x right, y down, z forward).
     */
    fun earthFromCvCamera(quaternion: FloatArray, glCameraFromDevice: FloatArray): FloatArray {
        require(quaternion.size == 4 && glCameraFromDevice.size == 9)
        val x = quaternion[0].toDouble(); val y = quaternion[1].toDouble()
        val z = quaternion[2].toDouble(); val w = quaternion[3].toDouble()
        val worldFromDevice = doubleArrayOf(
            1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w),
            2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w),
            2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y),
        )
        // cv ← device = diag(1, −1, −1) · gl ← device; earth ← cv = (world ← device) · (cv ← device)ᵀ.
        val sign = doubleArrayOf(1.0, -1.0, -1.0)
        return FloatArray(9) { i ->
            val r = i / 3; val c = i % 3
            var sum = 0.0
            for (k in 0..2) sum += worldFromDevice[r * 3 + k] * sign[c] * glCameraFromDevice[c * 3 + k]
            sum.toFloat()
        }
    }
}
