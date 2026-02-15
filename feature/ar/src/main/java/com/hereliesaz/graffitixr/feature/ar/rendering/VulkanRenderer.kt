package com.hereliesaz.graffitixr.feature.ar.rendering

import android.content.Context
import android.view.Surface
import com.hereliesaz.graffitixr.nativebridge.SlamManager

/**
 * Handles Vulkan rendering initialization and resizing.
 * Delegates actual native calls to SlamManager.
 */
class VulkanRenderer(
    private val context: Context,
    private val slamManager: SlamManager
) {

    /**
     * Initializes the Vulkan rendering subsystem.
     */
    fun onSurfaceCreated(surface: Surface) {
        // Pass the surface and assets to the native engine via SlamManager
        slamManager.initVulkan(surface, context.assets)
    }

    /**
     * Handles surface resize events.
     */
    fun onSurfaceChanged(width: Int, height: Int) {
        slamManager.resizeVulkan(width, height)
    }

    /**
     * Cleans up Vulkan resources.
     */
    fun onDestroy() {
        slamManager.destroyVulkan()
    }
}