package com.hereliesaz.graffitixr.graphics

import android.app.Activity
import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.hardware.display.DisplayManager.DisplayListener
import android.view.Display
import android.view.WindowManager
import com.google.ar.core.Session

/**
 * Helper to track the display rotations. In ARCore, we need to know the camera sensor orientation
 * and the display rotation value. This data is used by ARCore to transform camera images and
 * projection matrices.
 */
class DisplayRotationHelper(private val context: Context) : DisplayListener {
    private var isDeviceRotated = false
    private val display: Display
    private val displayManager: DisplayManager

    init {
        displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        display = windowManager.defaultDisplay
    }

    fun onResume() {
        displayManager.registerDisplayListener(this, null)
    }

    fun onPause() {
        displayManager.unregisterDisplayListener(this)
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        // No-op
    }

    fun updateSessionIfNeeded(session: Session) {
        if (isDeviceRotated) {
            val displayRotation = display.rotation
            session.setDisplayGeometry(displayRotation, display.width, display.height)
            isDeviceRotated = false
        }
    }

    override fun onDisplayAdded(displayId: Int) {}
    override fun onDisplayRemoved(displayId: Int) {}
    override fun onDisplayChanged(displayId: Int) {
        isDeviceRotated = true
    }
}