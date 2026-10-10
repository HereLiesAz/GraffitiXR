package com.hereliesaz.graffitixr.common.sensor

/**
 * Pinhole intrinsics in pixels of a [width] x [height] image. This is SphereSLAM's camera type; the
 * alias keeps the [SensorSource] vocabulary (`SensorSource.cameraIntrinsics`, `CameraIntrinsics.UNKNOWN`)
 * while the SLAM pipeline, the Camera2 estimator and the projection builder all speak one type.
 */
typealias CameraIntrinsics = com.hereliesaz.sphereslam.camera.CameraIntrinsics
