package com.vuforia

import android.app.Activity
import android.content.res.AssetManager
import java.nio.ByteBuffer

object VuforiaJNI {
    init {
        System.loadLibrary("VuforiaEngine")
    }

    external fun initAR(activity: Activity, assetManager: AssetManager, target: Int)
    external fun startAR(): Boolean
    external fun stopAR()
    external fun deinitAR()

    external fun cameraPerformAutoFocus()
    external fun cameraRestoreAutoFocus()

    external fun initRendering()
    external fun setTextures(
        astronautWidth: Int, astronautHeight: Int, astronautByteBuffer: ByteBuffer,
        landerWidth: Int, landerHeight: Int, landerByteBuffer: ByteBuffer
    )
    external fun setOverlayTexture(width: Int, height: Int, byteBuffer: ByteBuffer)
    external fun deinitRendering()

    external fun configureRendering(width: Int, height: Int, orientation: Int, rotation: Int): Boolean
    external fun renderFrame(): Boolean

    @JvmStatic
    external fun getImageTargetId(): Int
    @JvmStatic
    external fun getModelTargetId(): Int

    external fun createImageTarget(): Boolean
}
