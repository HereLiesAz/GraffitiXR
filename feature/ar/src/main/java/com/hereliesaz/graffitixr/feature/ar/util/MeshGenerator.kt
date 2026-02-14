package com.hereliesaz.graffitixr.feature.ar.util

import com.google.ar.core.CameraIntrinsics
import java.nio.ShortBuffer

/**
 * Utility to generate a 3D triangular mesh from ARCore depth maps.
 * Optimized for LiDAR-enabled devices.
 */
object MeshGenerator {

    /**
     * Generates a vertex array (x, y, z) from a 16-bit depth buffer.
     * Subsamples the depth map to create a low-poly representation.
     */
    fun generateMesh(
        depthBuffer: ShortBuffer,
        width: Int,
        height: Int,
        intrinsics: CameraIntrinsics,
        subsample: Int = 8
    ): FloatArray {
        val vertices = mutableListOf<Float>()
        
        val fx = intrinsics.focalLength[0]
        val fy = intrinsics.focalLength[1]
        val cx = intrinsics.principalPoint[0]
        val cy = intrinsics.principalPoint[1]

        for (y in 0 until height step subsample) {
            for (x in 0 until width step subsample) {
                val index = y * width + x
                if (index >= depthBuffer.capacity()) continue
                
                val depthMm = depthBuffer.get(index).toInt() and 0xFFFF
                if (depthMm == 0) continue

                val depthM = depthMm / 1000.0f
                
                // Project 2 pixel to 3D space
                val worldX = (x - cx) * depthM / fx
                val worldY = (y - cy) * depthM / fy
                val worldZ = -depthM // ARCore uses -Z for forward

                vertices.add(worldX)
                vertices.add(worldY)
                vertices.add(worldZ)
            }
        }

        return vertices.toFloatArray()
    }
}