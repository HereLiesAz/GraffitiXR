package com.hereliesaz.graffitixr

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Session
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.hereliesaz.graffitixr.utils.DisplayRotationHelper
import com.hereliesaz.graffitixr.rendering.BackgroundRenderer
import java.io.IOException

class ARCoreManager(private val context: Context) : DefaultLifecycleObserver {

    var session: Session? = null
        private set
    val backgroundRenderer = BackgroundRenderer()
    val displayRotationHelper = DisplayRotationHelper(context)

    override fun onResume(owner: LifecycleOwner) {
        super.onResume(owner)
        if (session == null) {
            try {
                val installStatus = ArCoreApk.getInstance().requestInstall(context as android.app.Activity, true)
                if (installStatus == ArCoreApk.InstallStatus.INSTALLED) {
                    session = Session(context)
                }
            } catch (e: Exception) {
                return
            }
        }

        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            session = null
            return
        }
        displayRotationHelper.onResume()
    }

    override fun onPause(owner: LifecycleOwner) {
        super.onPause(owner)
        session?.pause()
        displayRotationHelper.onPause()
    }

    fun onDrawFrame(width: Int, height: Int): Frame? {
        session?.let {
            displayRotationHelper.updateSessionIfNeeded(it)
            it.setCameraTextureName(backgroundRenderer.textureId)
            return try {
                it.update()
            } catch (e: CameraNotAvailableException) {
                null
            }
        }
        return null
    }

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)
        session?.close()
        session = null
    }
}
