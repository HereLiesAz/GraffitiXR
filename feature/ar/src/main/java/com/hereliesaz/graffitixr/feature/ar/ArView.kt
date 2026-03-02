package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceView
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import java.nio.ByteBuffer

class ArView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : SurfaceView(context, attrs) {

    private val slamManager = SlamManager()

    fun createOnGlThread() { }

    fun onSurfaceChanged(width: Int, height: Int) {
        slamManager.resizeVulkanSurface(width, height)
    }

    fun draw() {
        slamManager.nativeDraw()
    }

    fun setBitmap(width: Int, height: Int, pixels: ByteBuffer) {
        slamManager.nativeSetBitmap(width, height, pixels)
    }

    fun processTeleologicalFrame(yuvBuffer: ByteBuffer, width: Int, height: Int, stride: Int) {
        slamManager.processTeleologicalFrame(yuvBuffer, width, height, stride)
    }

    fun reset() {
        slamManager.reset()
    }
}
