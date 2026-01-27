package com.hereliesaz.graffitixr

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.compose.rememberNavController
import com.hereliesaz.graffitixr.ui.theme.GraffitiXRTheme
import com.hereliesaz.graffitixr.utils.LocationTracker
import com.hereliesaz.graffitixr.utils.ensureOpenCVLoaded

class MainActivity : ComponentActivity() {

    // Using the factory to ensure Application context is passed correctly
    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(application)
    }
    
    private val PERMISSION_REQUEST_CODE = 1001
    private lateinit var locationTracker: LocationTracker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Safety check for OpenCV (Double check in Activity is harmless and safer)
        ensureOpenCVLoaded()

        // 1. ENABLE EDGE-TO-EDGE
        enableEdgeToEdge()

        // 2. Initialize Helpers
        locationTracker = LocationTracker(this)

        // 3. Permissions & Data Loading
        checkAndRequestPermissions()
        viewModel.loadAvailableProjects(this)

        // 4. Set UI
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
        // Resume location updates if we have permission
        if (locationTracker.hasPermissions()) {
            locationTracker.startLocationUpdates { location ->
                viewModel.updateCurrentLocation(location)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Save battery by stopping GPS when not visible
        locationTracker.stopLocationUpdates()
        
        // Auto-save project state with a thumbnail
        com.hereliesaz.graffitixr.utils.captureWindow(this) { bitmap ->
            viewModel.autoSaveProject(this, bitmap)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Final cleanup
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
            // If we already have permissions, trigger the initial fetch
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
            // Re-check permissions after the dialog closes
            if (locationTracker.hasPermissions()) {
                fetchLocationAndSort()
            }
        }
    }
}
