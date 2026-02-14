package com.hereliesaz.graffitixr.feature.ar.rendering

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * A stub implementation of a Vulkan-based Renderer for future optimizations.
 * This class provides the entry point for switching the rendering backend from OpenGL ES to Vulkan.
 */
class VulkanRenderer(private val context: Context) : SurfaceHolder.Callback, DefaultLifecycleObserver {

    private var surfaceView: SurfaceView? = null

    fun attachToSurface(view: SurfaceView) {
        surfaceView = view
        view.holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        // TODO: Initialize Vulkan Instance and Device
        // initVulkan(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // TODO: Recreate Swapchain
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // TODO: Destroy Vulkan Resources
    }

    override fun onResume(owner: LifecycleOwner) {
        // Resume rendering loop
    }

    override fun onPause(owner: LifecycleOwner) {
        // Pause rendering loop
    }
}
