package com.hereliesaz.graffitixr

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.rememberNavController
import com.hereliesaz.graffitixr.natives.ensureOpenCVLoaded
import com.hereliesaz.graffitixr.design.GraffitiXRTheme
import com.hereliesaz.graffitixr.common.model.CaptureEvent
import com.hereliesaz.graffitixr.feature.ar.*
import com.hereliesaz.graffitixr.feature.ar.*
import com.hereliesaz.graffitixr.feature.ar.*
import com.hereliesaz.graffitixr.feature.ar.*
import com.hereliesaz.graffitixr.common.LocationTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(application)
    }

    private val PERMISSION_REQUEST_CODE = 1001
    private lateinit var locationTracker: LocationTracker
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
                    onRendererCreated = { renderer ->
                        arRenderer = renderer
                    }
                )
            }
        }

        forceCameraRelease()

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
                        else -> {}
                    }
                }
            }
        }
    }

    // Capture Volume Keys for Unlock Gesture
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            // Simple implementation: Unlock on either volume key
            if (viewModel.uiState.value.isTouchLocked) {
                viewModel.setTouchLocked(false)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun forceCameraRelease() {
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    cameraProvider.unbindAll()
                } catch (e: Exception) {
                    // Ignore
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            // Ignore
        }
    }

    override fun onResume() {
        super.onResume()

        if (viewModel.uiState.value.editorMode == EditorMode.AR) {
            forceCameraRelease()
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isFinishing && !isDestroyed) {
                    arRenderer?.onResume(this)
                }
            }, 500)
        } else {
            arRenderer?.onResume(this)
        }

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

        if (!isFinishing && !isDestroyed) {
            try {
                com.hereliesaz.graffitixr.utils.captureWindow(this) { bitmap ->
                    viewModel.autoSaveProject(this, bitmap)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        arRenderer?.cleanup()
        arRenderer = null
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

    @Deprecated("This method has been deprecated in favor of using the Activity Result API\n      which brings increased type safety via an {@link ActivityResultContract} and the prebuilt\n      contracts for common intents available in\n      {@link androidx.activity.result.contract.ActivityResultContracts}, provides hooks for\n      testing, and allow receiving results in separate, testable classes independent from your\n      activity. Use\n      {@link #registerForActivityResult(ActivityResultContract, ActivityResultCallback)} passing\n      in a {@link RequestMultiplePermissions} object for the {@link ActivityResultContract} and\n      handling the result in the {@link ActivityResultCallback#onActivityResult(Object) callback}.")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (locationTracker.hasPermissions()) {
                fetchLocationAndSort()
            }
        }
    }
}