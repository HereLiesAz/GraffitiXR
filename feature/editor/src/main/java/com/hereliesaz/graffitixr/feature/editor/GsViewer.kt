
package com.hereliesaz.graffitixr.feature.editor

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.hereliesaz.graffitixr.nativebridge.SlamManager

class GsViewer(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    private val slamManager = SlamManager()

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val success = slamManager.initVulkanEngine(
            surface = holder.surface,
            assetManager = context.assets,
            width = width,
            height = height
        )
        if (!success) {
            // initialization failure handling
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        slamManager.resizeVulkanSurface(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        slamManager.destroyVulkanEngine()
    }

    fun reset() {
        slamManager.reset()
    }
}
