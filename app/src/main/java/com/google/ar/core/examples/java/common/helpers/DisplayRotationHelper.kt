/*
 * Copyright 2017 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ar.core.examples.java.common.helpers

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.WindowManager
import com.google.ar.core.Session

/**
 * Helper to track the display rotations. In particular, the 180 degree rotations are not notified
 * by the onSurfaceChanged() callback, and thus must be tracked manually.
 */
class DisplayRotationHelper(private val context: Context) : DisplayManager.DisplayListener {
    private var viewportWidth = 0
    private var viewportHeight = 0
    private val display: Display?
    private var viewportChanged = false

    init {
        display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(WindowManager::class.java).defaultDisplay
        }
    }

    /** Registers the display listener. Should be called from [Activity.onResume].  */
    fun onResume() {
        context.getSystemService(DisplayManager::class.java).registerDisplayListener(this, null)
    }

    /** Unregisters the display listener. Should be called from [Activity.onPause].  */
    fun onPause() {
        context.getSystemService(DisplayManager::class.java).unregisterDisplayListener(this)
    }

    /**
     * Records a change in surface dimensions. This will be later used by [.updateSessionIfNeeded].
     * Should be called from [android.opengl.GLSurfaceView.Renderer.onSurfaceChanged].
     *
     * @param width the new width of the surface
     * @param height the new height of the surface
     */
    fun onSurfaceChanged(width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        viewportChanged = true
    }

    /**
     * Updates the session display geometry if a change was posted either by
     * [.onSurfaceChanged] or by [.onDisplayChanged].
     */
    fun updateSessionIfNeeded(session: Session) {
        if (viewportChanged) {
            display?.let {
                val displayRotation = it.rotation
                session.setDisplayGeometry(displayRotation, viewportWidth, viewportHeight)
            }
            viewportChanged = false
        }
    }

    override fun onDisplayAdded(displayId: Int) {}
    override fun onDisplayRemoved(displayId: Int) {}
    override fun onDisplayChanged(displayId: Int) {
        viewportChanged = true
    }
}