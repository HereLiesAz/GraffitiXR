package com.hereliesaz.graffitixr.feature.ar.rendering

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import javax.inject.Inject

/**
 * A stub implementation of a Vulkan-based Renderer for future optimizations.
 * This class provides the entry point for switching the rendering backend from OpenGL ES to Vulkan.
 */
class VulkanRenderer @Inject constructor(
    private val context: Context,
    private val slamManager: SlamManager
) : SurfaceHolder.Callback, DefaultLifecycleObserver {

    private var surfaceView: SurfaceView? = null

    fun attachToSurface(view: SurfaceView) {
        surfaceView = view
        view.holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        slamManager.initVulkan(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        slamManager.resizeVulkan(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        slamManager.destroyVulkan()
    }

    override fun onResume(owner: LifecycleOwner) {
        // Resume rendering loop
    }

    override fun onPause(owner: LifecycleOwner) {
        // Pause rendering loop
    }
}
