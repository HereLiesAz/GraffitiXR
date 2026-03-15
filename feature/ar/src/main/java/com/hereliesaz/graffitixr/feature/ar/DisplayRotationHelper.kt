// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/DisplayRotationHelper.kt
package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.DisplayManager.DisplayListener
import android.view.Display
import android.view.WindowManager
import com.google.ar.core.Session
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Helper to track the display rotations. In particular, the 180 degree rotations are not tracked by
 * the [android.app.Activity.onConfigurationChanged] callback.
 */
class DisplayRotationHelper(context: Context) : DisplayListener {
    private var viewportChanged = false
    private var viewportWidth = 0
    private var viewportHeight = 0
    private val display: Display
    private val displayManager: DisplayManager
    private val lock = ReentrantLock()

    init {
        displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        display = windowManager.defaultDisplay
    }

    /** Registers the display listener. Should be called from [android.app.Activity.onResume]. */
    fun onResume() {
        displayManager.registerDisplayListener(this, null)
    }

    /** Unregisters the display listener. Should be called from [android.app.Activity.onPause]. */
    fun onPause() {
        displayManager.unregisterDisplayListener(this)
    }

    /**
     * Records a change in surface size. This will be later used in [updateSessionIfNeeded]. Should be
     * called from [android.opengl.GLSurfaceView.Renderer.onSurfaceChanged].
     */
    fun onSurfaceChanged(width: Int, height: Int) {
        lock.withLock {
            viewportWidth = width
            viewportHeight = height
            viewportChanged = true
        }
    }

    /**
     * Updates the session display geometry if a change was registered in [onSurfaceChanged] or in
     * [onDisplayChanged]. Should be called from [android.opengl.GLSurfaceView.Renderer.onDrawFrame].
     *
     * @param session The session to update.
     */
    fun updateSessionIfNeeded(session: Session) {
        lock.withLock {
            if (viewportChanged) {
                val displayRotation = display.rotation
                session.setDisplayGeometry(displayRotation, viewportWidth, viewportHeight)
                viewportChanged = false
            }
        }
    }

    /**
     * Returns the aspect ratio of the viewport. Should be called from
     * [android.opengl.GLSurfaceView.Renderer.onSurfaceChanged].
     *
     * @return The aspect ratio of the viewport.
     */
    fun getViewportAspectRatio(): Float {
        lock.withLock {
            return if (viewportHeight == 0) 1.0f else viewportWidth.toFloat() / viewportHeight.toFloat()
        }
    }

    /**
     * Returns the current rotation of the display.
     *
     * @return The current rotation of the display.
     */
    fun getRotation(): Int {
        return display.rotation
    }

    override fun onDisplayAdded(displayId: Int) {}
    override fun onDisplayRemoved(displayId: Int) {}
    override fun onDisplayChanged(displayId: Int) {
        lock.withLock {
            viewportChanged = true
        }
    }
}
