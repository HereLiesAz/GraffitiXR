package com.hereliesaz.graffitixr.nativebridge.depth

import android.content.Context
import android.media.Image
import com.hereliesaz.graffitixr.common.util.CameraCapabilities
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

interface DepthProvider {
    fun isSupported(): Boolean
    fun processDepth(image: Image)
    fun processStereoPair(leftImage: Image, rightImage: Image)
}

class StereoDepthProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val slamManager: SlamManager
) : DepthProvider {

    override fun isSupported(): Boolean {
        return CameraCapabilities.hasLogicalMultiCameraSupport(context)
    }

    override fun processDepth(image: Image) {
        // No-op for stereo provider, it expects pairs
    }

    override fun processStereoPair(leftImage: Image, rightImage: Image) {
        if (isSupported()) {
            slamManager.feedStereoData(leftImage, rightImage)
        }
    }
}
