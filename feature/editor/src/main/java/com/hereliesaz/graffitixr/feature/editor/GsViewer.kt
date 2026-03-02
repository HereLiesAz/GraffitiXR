package com.hereliesaz.graffitixr.feature.editor

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * A SurfaceView specialized for high-performance Gaussian Splat rendering via Vulkan.
 */
@AndroidEntryPoint
class GsViewer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    @Inject
    lateinit var slamManager: SlamManager

    private var isVulkanInitialized = false

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        // Dimensions are not yet reliable here — init is deferred to surfaceChanged.
        if (!isVulkanSupported()) {
            Log.e("GsViewer", "Vulkan not supported on this device (API ${Build.VERSION.SDK_INT})")
        }
    }

    private fun isVulkanSupported(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (!isVulkanSupported() || width == 0 || height == 0) return
        if (!isVulkanInitialized) {
            val ok = slamManager.initVulkanEngine(
                surface = holder.surface,
                assetManager = context.assets,
                width = width,
                height = height
            )
            if (ok) {
                isVulkanInitialized = true
            } else {
                Log.e("GsViewer", "Vulkan init failed — check driver support and shader assets")
            }
        } else {
            slamManager.resizeVulkanSurface(width, height)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        slamManager.destroyVulkanEngine()
        slamManager.reset()
        isVulkanInitialized = false
    }
}
