package com.hereliesaz.graffitixr.feature.editor.rendering

import android.opengl.Matrix
import kotlin.math.cos
import kotlin.math.sin

class VirtualCamera {
    val viewMatrix = FloatArray(16)
    val projectionMatrix = FloatArray(16)

    private var radius = 2.0f
    private var azimuth = 0.0f
    private var elevation = 0.5f

    private var targetX = 0.0f
    private var targetY = 0.0f
    private var targetZ = 0.0f

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
        val sensitivity = 0.005f
        azimuth -= dx * sensitivity
        elevation += dy * sensitivity

        val limit = 1.5f
        if (elevation > limit) elevation = limit
        if (elevation < -limit) elevation = -limit

        updateViewMatrix()
    }

    fun handlePinch(scaleFactor: Float) {
        radius /= scaleFactor
        if (radius < 0.1f) radius = 0.1f
        if (radius > 20.0f) radius = 20.0f
        updateViewMatrix()
    }

    fun handlePan(dx: Float, dy: Float) {
        val sensitivity = 0.001f * radius

        val rightX = cos(azimuth)
        val rightZ = -sin(azimuth)

        val upX = -sin(azimuth) * sin(elevation)
        val upY = cos(elevation)
        val upZ = -cos(azimuth) * sin(elevation)

        targetX -= (rightX * dx + upX * dy) * sensitivity
        targetY -= (upY * dy) * sensitivity
        targetZ -= (rightZ * dx + upZ * dy) * sensitivity

        updateViewMatrix()
    }

    private fun updateViewMatrix() {
        val x = targetX + radius * cos(elevation) * sin(azimuth)
        val y = targetY + radius * sin(elevation)
        val z = targetZ + radius * cos(elevation) * cos(azimuth)

        Matrix.setLookAtM(viewMatrix, 0,
            x, y, z,
            targetX, targetY, targetZ,
            0f, 1f, 0f
        )
    }

    private fun updateProjectionMatrix() {
        Matrix.perspectiveM(projectionMatrix, 0, 60.0f, aspect, 0.1f, 100.0f)
    }
}