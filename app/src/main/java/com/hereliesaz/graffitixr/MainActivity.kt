package com.hereliesaz.graffitixr

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge // REQUIRED for AzNavRail Strict Layouts
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.compose.rememberNavController
import com.hereliesaz.graffitixr.ui.theme.GraffitiXRTheme
import com.hereliesaz.graffitixr.utils.LocationTracker
import com.hereliesaz.graffitixr.utils.ensureOpenCVLoaded

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(application)
    }
    private val PERMISSION_REQUEST_CODE = 0
    private lateinit var locationTracker: LocationTracker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize OpenCV early
        ensureOpenCVLoaded()

        // 1. ENABLE EDGE-TO-EDGE
        // This allows the "background" layer of AzHostActivityLayout to draw
        // behind the system navigation and status bars.
        enableEdgeToEdge()

        // Request Permissions (Camera + Location)
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
        }

        // Load projects immediately
        viewModel.loadAvailableProjects(this)

        // Initialize LocationTracker
        locationTracker = LocationTracker(this)

        // Try to get location and sort
        fetchLocationAndSort()

        setContent {
            val navController = rememberNavController()
            GraffitiXRTheme {
                MainScreen(
                    viewModel = viewModel,
                    navController = navController
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (locationTracker.hasPermissions()) {
            locationTracker.startLocationUpdates { location ->
                viewModel.updateCurrentLocation(location)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        locationTracker.stopLocationUpdates()
        com.hereliesaz.graffitixr.utils.captureWindow(this) { bitmap ->
            viewModel.autoSaveProject(this, bitmap)
        }
    }

    private fun fetchLocationAndSort() {
        if (locationTracker.hasPermissions()) {
            // Initial fetch
            locationTracker.startLocationUpdates { location ->
                viewModel.updateCurrentLocation(location)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            fetchLocationAndSort()
        }
    }
}
