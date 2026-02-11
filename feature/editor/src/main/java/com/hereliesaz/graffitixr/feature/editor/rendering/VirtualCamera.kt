package com.hereliesaz.graffitixr.feature.editor.rendering

import android.opengl.Matrix
import kotlin.math.cos
import kotlin.math.sin

class VirtualCamera {
    // Matrices
    val viewMatrix = FloatArray(16)
    val projectionMatrix = FloatArray(16)

    // Camera State (Orbit System)
    private var radius = 2.0f // Distance from target (Zoom)
    private var azimuth = 0.0f // Horizontal rotation (radians)
    private var elevation = 0.5f // Vertical rotation (radians)

    // Target (Where we are looking)
    private var targetX = 0.0f
    private var targetY = 0.0f
    private var targetZ = 0.0f

    // Screen info
    private var aspect = 1.0f

    init {
        Matrix.setIdentityM(viewMatrix, 0)
        Matrix.setIdentityM(projectionMatrix, 0)
        updateViewMatrix()
    }

    fun setAspectRatio(width: Int, height: Int) {
        if (height == 0) return
        aspect = width.toFloat() / height.toFloat()
        updateProjectionMatrix()
    }

    fun handleDrag(dx: Float, dy: Float) {
        // Orbit sensitivity
        val sensitivity = 0.005f
        azimuth -= dx * sensitivity
        elevation += dy * sensitivity

        // Clamp elevation to avoid gimbal lock flip
        val limit = 1.5f // Slightly less than PI/2
        if (elevation > limit) elevation = limit
        if (elevation < -limit) elevation = -limit

        updateViewMatrix()
    }

    fun handlePinch(scaleFactor: Float) {
        // Zoom (Inverse scale)
        radius /= scaleFactor

        // Limits
        if (radius < 0.1f) radius = 0.1f
        if (radius > 20.0f) radius = 20.0f

        updateViewMatrix()
    }

    fun handlePan(dx: Float, dy: Float) {
        // Pan relative to camera orientation
        // This requires recalculating forward/right vectors
        // For simplicity, we just move target on X/Y plane for now
        val sensitivity = 0.001f * radius

        // Simple X/Y pan (Camera centric would be better)
        val forwardX = sin(azimuth)
        val forwardZ = cos(azimuth)
        val rightX = cos(azimuth)
        val rightZ = -sin(azimuth)

        // Move target
        targetX -= (rightX * dx + forwardX * dy) * sensitivity
        targetZ -= (rightZ * dx + forwardZ * dy) * sensitivity
        // targetY += dy * sensitivity // Optional vertical pan

        updateViewMatrix()
    }

    private fun updateViewMatrix() {
        // Spherical to Cartesian
        val x = targetX + radius * cos(elevation) * sin(azimuth)
        val y = targetY + radius * sin(elevation)
        val z = targetZ + radius * cos(elevation) * cos(azimuth)

        // LookAt
        Matrix.setLookAtM(viewMatrix, 0,
            x, y, z,          // Eye
            targetX, targetY, targetZ, // Center
            0f, 1f, 0f        // Up
        )
    }

    private fun updateProjectionMatrix() {
        // 60 degree FOV
        Matrix.perspectiveM(projectionMatrix, 0, 60.0f, aspect, 0.1f, 100.0f)
    }
}