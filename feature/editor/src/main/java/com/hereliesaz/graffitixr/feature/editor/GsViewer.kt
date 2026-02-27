package com.hereliesaz.graffitixr.feature.editor

import android.content.Context
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

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val width = width
        val height = height
        val assetManager = context.assets

        val ok = slamManager.initVulkanEngine(
            surface = holder.surface,
            assetManager = assetManager,
            width = width,
            height = height
        )
        if (!ok) Log.e("GsViewer", "Vulkan init failed — device may not support required features")
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // Fixed: Passing new dimensions on surface resize
        slamManager.resizeVulkanSurface(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        slamManager.destroyVulkanEngine()
        slamManager.reset()
    }
}
