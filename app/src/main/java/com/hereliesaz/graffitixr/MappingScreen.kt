package com.hereliesaz.graffitixr

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.provider.MediaStore
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.rememberNavController
import com.hereliesaz.aznavrail.AzNavRail
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.aznavrail.model.AzHeaderIconShape
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.sphereslam.SphereSLAM
import java.io.File
import java.io.FileOutputStream
import android.os.Handler
import android.os.HandlerThread

@Composable
fun MappingScreen(
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current

    // SphereSLAM State
    var sphereSLAM by remember { mutableStateOf<SphereSLAM?>(null) }

    // Sensor Thread
    var sensorThread by remember { mutableStateOf<HandlerThread?>(null) }

    // Sensor Manager
    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    var accelerometer by remember { mutableStateOf<Sensor?>(null) }
    var gyroscope by remember { mutableStateOf<Sensor?>(null) }

    // UI State
    var showInstructions by remember { mutableStateOf(true) }
    var statsText by remember { mutableStateOf("") }
    var trackingState by remember { mutableStateOf(-1) } // 0: No Images, 1: Not Init, 2: Tracking, 3: Lost

    // Launchers
    val aospCameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        // No result data expected from standard camera intent usually
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                inputStream?.use { input ->
                    // Save to project dir
                    val projectDir = context.getExternalFilesDir(null)
                    val destFile = File(projectDir, "photosphere.jpg")
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }

                    // Also save to cache dir for SphereSLAM if needed
                    val cacheDir = context.cacheDir
                    val cacheFile = File(cacheDir, "photosphere.jpg")
                    cacheFile.writeBytes(destFile.readBytes())

                    Toast.makeText(context, "Photosphere Imported Successfully", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Import Failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Check for AOSP Camera
    val packageManager = context.packageManager
    val aospCameraPackage = "com.android.camera"
    val hasAospCamera = remember {
        try {
            packageManager.getPackageInfo(aospCameraPackage, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    // Initialize SphereSLAM
    DisposableEffect(Unit) {
        android.util.Log.v("MappingScreen", "Initializing SphereSLAM")
        val slamCacheDir = java.io.File(context.cacheDir, "sphereslam_cache")
        if (!slamCacheDir.exists()) slamCacheDir.mkdirs()

        val slam = SphereSLAM(context)
        sphereSLAM = slam

        val thread = HandlerThread("SensorThread")
        thread.start()
        sensorThread = thread

        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        // Note: Manual camera capture via SphereCameraManager removed.
        // We rely on external photosphere creation and import.

        onDispose {
            android.util.Log.v("MappingScreen", "Cleaning up SphereSLAM")
            slam.cleanup()
            thread.quitSafely()
        }
    }

    // Sensor Listener
    val sensorListener = remember {
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                event?.let {
                    if (sphereSLAM != null) {
                         sphereSLAM!!.processIMU(it.sensor.type, it.values[0], it.values[1], it.values[2], it.timestamp)
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
    }

    // Choreographer Frame Callback
    val frameCallback = remember {
        object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                sphereSLAM?.renderFrame()

                if (frameTimeNanos % 60 == 0L) {
                    sphereSLAM?.let {
                        trackingState = it.getTrackingState()
                        statsText = it.getMapStats()
                    }
                }
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    // Lifecycle Management
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    android.util.Log.v("MappingScreen", "Lifecycle: ON_RESUME")
                    val handler = sensorThread?.let { Handler(it.looper) }
                    accelerometer?.let { sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_FASTEST, handler) }
                    gyroscope?.let { sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_FASTEST, handler) }
                    Choreographer.getInstance().postFrameCallback(frameCallback)
                }
                Lifecycle.Event.ON_PAUSE -> {
                    android.util.Log.v("MappingScreen", "Lifecycle: ON_PAUSE")
                    Choreographer.getInstance().removeFrameCallback(frameCallback)
                    sensorManager.unregisterListener(sensorListener)
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val navController = rememberNavController()

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. SphereSLAM Render Surface
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            sphereSLAM?.setNativeWindow(holder.surface)
                        }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            sphereSLAM?.setNativeWindow(null)
                        }
                    })
                }
            }
        )

        // 2. Nav Rail Layer
        AzNavRail(
            navController = navController,
            currentDestination = "surveyor",
            isLandscape = false
        ) {
            azSettings(
                displayAppNameInHeader = true,
                headerIconShape = AzHeaderIconShape.ROUNDED
            )

            azRailItem(
                id = "back",
                text = "Back",
                route = "back",
                onClick = {
                    android.util.Log.v("MappingScreen", "Button: Back clicked")
                    onExit()
                }
            )

            azRailItem(
                id = "help",
                text = "Help",
                route = "help",
                onClick = {
                    showInstructions = !showInstructions
                }
            )
        }

        // 3. UI Overlay Layer
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 80.dp)
        ) {
            // Stats / State Overlay
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                val stateStr = when(trackingState) {
                    -1 -> "SYSTEM NOT READY"
                    0 -> "NO IMAGES"
                    1 -> "NOT INITIALIZED"
                    2 -> "TRACKING"
                    3 -> "LOST"
                    else -> "UNKNOWN"
                }
                Text(
                    text = "State: $stateStr\n$statsText",
                    color = Color.Green,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // Instructions Overlay
            if (showInstructions) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 16.dp, start = 16.dp)
                        .background(Color(0x80000000))
                        .padding(16.dp)
                ) {
                    Text(
                        text = "SURVEYOR MODE\n\n1. Use 'Launch Camera' to take a photosphere.\n2. Use 'Import Photosphere' to load it.",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            // Controls
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)
            ) {
                if (hasAospCamera) {
                    AzButton(
                        text = "Launch Camera",
                        shape = AzButtonShape.RECTANGLE,
                        onClick = {
                            val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                            intent.setPackage(aospCameraPackage)
                            // Try to target the main activity directly as requested
                            intent.setClassName(aospCameraPackage, "com.android.camera.CameraActivity")
                            try {
                                aospCameraLauncher.launch(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Could not launch AOSP Camera", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }

                AzButton(
                    text = "Import Photosphere",
                    shape = AzButtonShape.RECTANGLE,
                    onClick = {
                        importLauncher.launch("image/*")
                    }
                )
            }
        }
    }
}
