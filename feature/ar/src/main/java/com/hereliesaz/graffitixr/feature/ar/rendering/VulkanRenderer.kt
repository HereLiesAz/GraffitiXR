package com.hereliesaz.graffitixr.feature.ar.rendering

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import android.view.Surface
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * High-performance Vulkan-based renderer for AR overlays.
 */
class VulkanRenderer @Inject constructor(
    private val slamManager: SlamManager,
    @ApplicationContext private val context: Context
) {
    var isReady = false
        private set

    fun onSurfaceCreated(surface: Surface, assetManager: AssetManager, width: Int, height: Int) {
        if (!isVulkanSupported()) {
            Log.e("VulkanRenderer", "Vulkan not supported on this device (API ${Build.VERSION.SDK_INT})")
            return
        }
        isReady = slamManager.initVulkan(surface, assetManager, width, height)
        if (!isReady) Log.e("VulkanRenderer", "Vulkan init failed — check driver support and shader assets")
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        slamManager.resizeVulkan(width, height)
    }

    fun onSurfaceDestroyed() {
        slamManager.destroyVulkan()
    }

    private fun isVulkanSupported(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
    }
}
