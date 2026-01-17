package com.hereliesaz.graffitixr

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hereliesaz.graffitixr.ui.theme.GraffitiXRTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var surfaceView: GLSurfaceView
    private var arRenderer: ArRenderer? = null

    // We need to verify these before booting the AR engine
    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION
    )
    private val PERMISSION_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Fullscreen / Immersive Mode
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_CODE)
        } else {
            setupAr()
        }
    }

    private fun setupAr() {
        // Create the SurfaceView programmatically or inflate it.
        // For a pure mix of Compose + GL, we can use AndroidView, but
        // managing the GLSurfaceView lifecycle is easier if we own the instance here
        // and pass it to Compose or overlay Compose on top.

        // Strategy: Use SetContent for the UI overlay, but the GLSurfaceView
        // needs to be part of the layout.
        // To keep it clean, I will assume MainScreen handles the AndroidView wrapping
        // or we set it as the content view and add Compose as an overlay.

        // HOWEVER, to ensure the Renderer gets the lifecycle callbacks,
        // we will instantiate the Renderer here and pass it to the ViewModel/UI.

        arRenderer = ArRenderer(
            context = this,
            onPlanesDetected = { detected -> viewModel.setArPlanesDetected(detected) },
            onFrameCaptured = { bitmap -> viewModel.onFrameCaptured(bitmap) },
            onAnchorCreated = { viewModel.onArImagePlaced() },
            onProgressUpdated = { progress, bmp -> viewModel.onProgressUpdate(progress, bmp) },
            onTrackingFailure = { msg -> viewModel.onTrackingFailure(msg) },
            onBoundsUpdated = { rect -> viewModel.updateArtworkBounds(rect) }
        )

        // Bind Renderer to ViewModel so logic can flow back (e.g. capture trigger)
        viewModel.arRenderer = arRenderer

        setContent {
            GraffitiXRTheme {
                // MainScreen now takes the renderer to embed in the AndroidView
                MainScreen(
                    viewModel = viewModel,
                    arRenderer = arRenderer!!,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        if (hasPermissions()) {
            try {
                arRenderer?.onResume(this)
            } catch (e: Exception) {
                Log.e("MainActivity", "Error resuming AR session", e)
                Toast.makeText(this, "Failed to resume AR: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        arRenderer?.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        arRenderer?.cleanup()
    }

    private fun hasPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                setupAr()
            } else {
                Toast.makeText(this, "Camera and Location permissions are required for AR.", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }
}