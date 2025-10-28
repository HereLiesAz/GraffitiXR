package com.hereliesaz.graffitixr.utils

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import com.google.ar.core.Session

class DisplayRotationHelper(private val context: Context) : DisplayManager.DisplayListener {
    private var viewportChanged = false
    private var viewportWidth = 0
    private var viewportHeight = 0
    private val display: Display? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(WindowManager::class.java).defaultDisplay
        }
    }


    fun onResume() {
        context.getSystemService(DisplayManager::class.java).registerDisplayListener(this, null)
    }

    fun onPause() {
        context.getSystemService(DisplayManager::class.java).unregisterDisplayListener(this)
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        viewportChanged = true
    }

    fun updateSessionIfNeeded(session: Session) {
        val localDisplay = display
        if (viewportChanged && localDisplay != null) {
            val displayRotation = when (localDisplay.rotation) {
                Surface.ROTATION_0 -> 0
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            session.setDisplayGeometry(displayRotation, viewportWidth, viewportHeight)
            viewportChanged = false
        }
    }

    override fun onDisplayAdded(displayId: Int) {}
    override fun onDisplayRemoved(displayId: Int) {}
    override fun onDisplayChanged(displayId: Int) {
        viewportChanged = true
    }
}
