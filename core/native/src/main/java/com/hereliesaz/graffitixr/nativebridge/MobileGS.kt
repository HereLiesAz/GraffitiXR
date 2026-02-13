package com.hereliesaz.graffitixr.core.nativebridge

import java.nio.ByteBuffer

/**
 * Mobile Gaussian Splatting (MobileGS) Native Interface.
 * Acts as the bridge between the Kotlin AR Logic an1d the C++ Rendering Engine.
 */
object MobileGS {

    init {
        try {
            System.loadLibrary("mobilegs")
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
        }
    }

    /**
     * Initialize the native engine and allocate memory.
     */
    external fun init()

    /**
     * Free all native memory and destroy chunks.
     * Must be called in onDestroy().
     */
    external fun cleanup()

    /**
     * Manually add a single Gaussian Splat to the scene.
     */
    external fun addGaussian(
        chunkId: Int,
        x: Float, y: Float, z: Float,
        r: Float, g: Float, b: Float, opacity: Float,
        sx: Float, sy: Float, sz: Float,
        rw: Float, rx: Float, ry: Float, rz: Float
    )

    /**
     * Get the total number of active gaussians in the scene.
     */
    external fun getGaussianCount(): Int

    /**
     * Ingest a raw Depth Buffer and generate 3D Gaussian Splats.
     * * @param byteBuffer Direct ByteBuffer containing 16-bit depth data (ImageFormat.DEPTH16).
     * @param width Width of the depth image.
     * @param height Height of the depth image.
     * @param poseMatrix A 16-element Float Array representing the column-major 4x4 View Matrix (Camera to World).
     */
    external fun updateSlam(
        byteBuffer: ByteBuffer,
        width: Int,
        height: Int,
        poseMatrix: FloatArray
    )
}