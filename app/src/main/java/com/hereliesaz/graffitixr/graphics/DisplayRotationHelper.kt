package com.hereliesaz.graffitixr.graphics

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.DisplayManager.DisplayListener
import android.os.Build
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
    private val display: Display?
    private val displayManager: DisplayManager

    init {
        displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        }
    }

    fun onResume() {
        displayManager.registerDisplayListener(this, null)
    }

    fun onPause() {
        displayManager.unregisterDisplayListener(this)
    }

    fun updateSessionIfNeeded(session: Session) {
        if (isDeviceRotated && display != null) {
            val displayRotation = display.rotation
            val displaySize = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(displaySize)
            session.setDisplayGeometry(displayRotation, displaySize.x, displaySize.y)
            isDeviceRotated = false
        }
    }

    override fun onDisplayAdded(displayId: Int) {}
    override fun onDisplayRemoved(displayId: Int) {}
    override fun onDisplayChanged(displayId: Int) {
        isDeviceRotated = true
    }
}