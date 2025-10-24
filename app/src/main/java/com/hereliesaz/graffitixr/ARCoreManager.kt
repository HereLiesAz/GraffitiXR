package com.hereliesaz.graffitixr

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Session

class ARCoreManager(private val context: Context) : DefaultLifecycleObserver {

    var session: Session? = null
        private set

    override fun onResume(owner: LifecycleOwner) {
        super.onResume(owner)
        if (session == null) {
            try {
                // Request installation of ARCore if it's not already installed.
                val installStatus = ArCoreApk.getInstance().requestInstall(context as android.app.Activity, true)
                if (installStatus == ArCoreApk.InstallStatus.INSTALLED) {
                    // ARCore is installed, create the session.
                    session = Session(context)
                }
            } catch (e: Exception) {
                // Handle exceptions.
            }
        }
    }

    override fun onPause(owner: LifecycleOwner) {
        super.onPause(owner)
        session?.pause()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)
        session?.close()
        session = null
    }
}
