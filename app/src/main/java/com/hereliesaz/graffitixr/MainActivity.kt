package com.hereliesaz.graffitixr

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import com.hereliesaz.graffitixr.ui.theme.GraffitiXRTheme

class MainActivity : ComponentActivity() {

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var renderer: MuralRenderer
    private var session: Session? = null
    private var userRequestedInstall = true

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                // Ensure GLSurfaceView is resumed as its lifecycle might be desynced
                // if the permission dialog was an overlay.
                if (::glSurfaceView.isInitialized) {
                    glSurfaceView.onResume()
                }
                setupArSession() // Creates and configures the session
                // Now, also resume the newly created session
                try {
                    session?.resume()
                } catch (e: CameraNotAvailableException) {
                    Toast.makeText(this, "Camera not available after granting permission.", Toast.LENGTH_LONG).show()
                    session = null // Invalidate session
                    finish()
                }
            } else {
                Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        renderer = MuralRenderer(this)

        setContent {
            GraffitiXRTheme {
                SurfaceView()
            }
        }
    }

    @Composable
    fun SurfaceView() {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(factory = { context ->
                GLSurfaceView(context).apply {
                    setEGLContextClientVersion(3)
                    setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                }.also {
                    glSurfaceView = it
                }
            }, modifier = Modifier.fillMaxSize())
        }
    }

    override fun onResume() {
        super.onResume()

        // Resume GLSurfaceView first
        if (::glSurfaceView.isInitialized) {
            glSurfaceView.onResume()
        }

        if (session == null) {
            checkAndRequestPermissions() // This attempts to create the session if needed
        }

        // Attempt to resume the session (either existing or newly created by checkAndRequestPermissions sync path)
        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            Toast.makeText(this, "Camera not available. Please restart the application", Toast.LENGTH_LONG).show()
            session = null // Invalidate session
            finish()
        }
    }

    override fun onPause() {
        super.onPause()
        if (session != null) {
            if (::glSurfaceView.isInitialized) {
                glSurfaceView.onPause()
            }
            session!!.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.close()
        session = null
    }

    private fun checkAndRequestPermissions() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                setupArSession()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                 Toast.makeText(this, "Camera permission is needed for AR", Toast.LENGTH_LONG).show()
                 requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun setupArSession() {
        if (session != null) {
            return
        }

        try {
            val installStatus = ArCoreApk.getInstance().requestInstall(this, userRequestedInstall)
            if (installStatus == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                userRequestedInstall = false
                return
            }

            if (ArCoreApk.getInstance().checkAvailability(this).isSupported) {
                session = Session(this)
                renderer.setSession(session!!)
            } else {
                Toast.makeText(this, "ARCore not supported on this device", Toast.LENGTH_LONG).show()
                finish()
            }
        } catch (e: UnavailableUserDeclinedInstallationException) {
            Toast.makeText(this, "Please install ARCore", Toast.LENGTH_LONG).show()
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to create AR session: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
            finish()
        }
    }
}
