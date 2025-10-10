package com.hereliesaz.graffitixr

import java.nio.ByteBuffer

object VuforiaJNI {
    init {
        System.loadLibrary("graffitixr")
    }

    // Engine Lifecycle
    external fun engineCreate(configSet: Long): Long
    external fun engineDestroy(engine: Long)
    external fun engineStart(engine: Long): Boolean
    external fun engineStop(engine: Long): Boolean

    // Configuration
    external fun configSetCreate(): Long
    external fun configSetDestroy(configSet: Long)
    external fun configSetAddPlatformAndroidConfig(configSet: Long, activity: Any)
    external fun configSetAddLicenseConfig(configSet: Long, licenseKey: String)

    // Rendering
    external fun initRendering(): Int
    external fun configureRendering(width: Int, height: Int, orientation: Int)
    external fun renderFrame(engine: Long): Boolean

    // Target Management
    external fun createImageTarget(engine: Long): Boolean
    external fun setOverlayTexture(width: Int, height: Int, pixels: ByteBuffer)
    external fun setTextures(textures: Array<Any>)
}
