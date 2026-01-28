package com.hereliesaz.graffitixr

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.rememberNavController
import com.hereliesaz.graffitixr.data.CaptureEvent
import com.hereliesaz.graffitixr.ui.theme.GraffitiXRTheme
import com.hereliesaz.graffitixr.utils.LocationTracker
import com.hereliesaz.graffitixr.utils.ensureOpenCVLoaded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(application)
    }
    
    private val PERMISSION_REQUEST_CODE = 1001
    private lateinit var locationTracker: LocationTracker
    
    // FIX: Activity holds the renderer, not ViewModel
    private var arRenderer: ArRenderer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureOpenCVLoaded()
        enableEdgeToEdge()

        locationTracker = LocationTracker(this)
        checkAndRequestPermissions()
        viewModel.loadAvailableProjects(this)

        setContent {
            val navController = rememberNavController()
            GraffitiXRTheme {
                MainScreen(
                    viewModel = viewModel,
                    navController = navController,
                    // FIX: Callback to capture the renderer instance when View creates it
                    onRendererCreated = { renderer ->
                        arRenderer = renderer
                    }
                )
            }
        }
        
        // FIX: Event Observer
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.captureEvent.collect { event ->
                    when (event) {
                        is CaptureEvent.RequestCapture -> arRenderer?.triggerCapture()
                        is CaptureEvent.RequestCalibration -> {
                            val pose = arRenderer?.getLatestPose()
                            if (pose != null) {
                                val matrix = FloatArray(16)
                                pose.toMatrix(matrix, 0)
                                viewModel.onCalibrationPointCaptured(matrix)
                            }
                        }
                        is CaptureEvent.RequestFingerprint -> {
                            val fp = arRenderer?.generateFingerprint(event.bitmap)
                            viewModel.onFingerprintGenerated(fp)
                        }
                        is CaptureEvent.RequestMapSave -> {
                            // FIX: Offload synchronous native save to IO thread
                            val path = event.path
                            val renderer = arRenderer
                            if (renderer != null) {
                                launch(Dispatchers.IO) {
                                    val success = renderer.slamManager.saveWorld(path)
                                    withContext(Dispatchers.Main) {
                                        val msg = if (success) "Map saved" else "Save failed"
                                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        arRenderer?.onResume(this)
        viewModel.onResume()
        if (locationTracker.hasPermissions()) {
            locationTracker.startLocationUpdates { location ->
                viewModel.updateCurrentLocation(location)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        arRenderer?.onPause()
        viewModel.onPause()
        locationTracker.stopLocationUpdates()
        com.hereliesaz.graffitixr.utils.captureWindow(this) { bitmap ->
            viewModel.autoSaveProject(this, bitmap)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        arRenderer?.cleanup()
        arRenderer = null // Drop reference
        locationTracker.stopLocationUpdates()
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            fetchLocationAndSort()
        }
    }

    private fun fetchLocationAndSort() {
        if (locationTracker.hasPermissions()) {
            locationTracker.startLocationUpdates { location ->
                viewModel.updateCurrentLocation(location)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (locationTracker.hasPermissions()) {
                fetchLocationAndSort()
            }
        }
    }
}
