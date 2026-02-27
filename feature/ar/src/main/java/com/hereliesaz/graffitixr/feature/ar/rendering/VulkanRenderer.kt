package com.hereliesaz.graffitixr.feature.ar.rendering

import android.content.res.AssetManager
import android.view.Surface
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import javax.inject.Inject

/**
 * High-performance Vulkan-based renderer for AR overlays.
 */
class VulkanRenderer @Inject constructor(
    private val slamManager: SlamManager
) {
    /**
     * Initializes the native Vulkan pipeline.
     */
    var isReady = false
        private set

    fun onSurfaceCreated(surface: Surface, assetManager: AssetManager, width: Int, height: Int) {
        isReady = slamManager.initVulkan(surface, assetManager, width, height)
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        slamManager.resizeVulkan(width, height)
    }

    fun onSurfaceDestroyed() {
        slamManager.destroyVulkan()
    }
}