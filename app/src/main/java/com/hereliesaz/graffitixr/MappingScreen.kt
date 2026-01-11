package com.hereliesaz.graffitixr

import android.Manifest
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.AzNavRail
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.aznavrail.model.AzHeaderIconShape
import com.hereliesaz.graffitixr.slam.SlamReflectionHelper
import com.hereliesaz.sphereslam.SphereCameraManager
import com.hereliesaz.sphereslam.SphereSLAM
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.core.Mat
import java.io.File

@Composable
fun MappingScreen(
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // SphereSLAM State
    var sphereSLAM by remember { mutableStateOf<SphereSLAM?>(null) }
    var cameraManager by remember { mutableStateOf<SphereCameraManager?>(null) }

    // Sensor Thread
    var sensorThread by remember { mutableStateOf<HandlerThread?>(null) }

    // Sensor Manager
    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    var accelerometer by remember { mutableStateOf<Sensor?>(null) }
    var gyroscope by remember { mutableStateOf<Sensor?>(null) }

    // UI State
    var isMapping by remember { mutableStateOf(false) }
    var showInstructions by remember { mutableStateOf(true) }
    var statsText by remember { mutableStateOf("") }
    var trackingState by remember { mutableStateOf(-1) } // 0: No Images, 1: Not Init, 2: Tracking, 3: Lost

    // Permissions
    var hasCameraPermission by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (isGranted) {
             Toast.makeText(context, "Camera Permission Granted", Toast.LENGTH_SHORT).show()
        } else {
             Toast.makeText(context, "Camera Permission Denied", Toast.LENGTH_SHORT).show()
        }
    }

    // File Pickers
    val loadMapLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            // Copy to temp file and load
            scope.launch(Dispatchers.IO) {
                try {
                    val inputStream = context.contentResolver.openInputStream(it)
                    val tempFile = File(context.cacheDir, "loaded_map.bin")
                    inputStream?.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    val success = sphereSLAM?.let { slam ->
                        SlamReflectionHelper.loadMap(slam, tempFile.absolutePath)
                    } ?: false

                    withContext(Dispatchers.Main) {
                        if (success) {
                            Toast.makeText(context, "Map Loaded Successfully", Toast.LENGTH_SHORT).show()
                            isMapping = true // Switch to mapping mode to view
                        } else {
                            Toast.makeText(context, "Failed to load map", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Error loading map: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // Initialize SphereSLAM
    DisposableEffect(Unit) {
        android.util.Log.v("MappingScreen", "Initializing SphereSLAM")
        
        val slam = try {
            SphereSLAM(context)
        } catch (e: Exception) {
            android.util.Log.e("MappingScreen", "Failed to create SphereSLAM", e)
            null
        }
        sphereSLAM = slam

        if (slam != null) {
            SlamReflectionHelper.initialize(slam)
        }

        val thread = HandlerThread("SensorThread")
        thread.start()
        sensorThread = thread

        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        var yMat: Mat? = null
        var reusableByteArray: ByteArray? = null

        val camManager = SphereCameraManager(context) { image ->
             try {
                 if (sphereSLAM == null) return@SphereCameraManager
                 
                 val planes = image.planes
                 val yPlane = planes[0]
                 val ySize = yPlane.buffer.remaining()

                 // Reallocate if needed
                 val currentYMat = yMat
                 if (currentYMat == null || currentYMat.cols() != image.width || currentYMat.rows() != image.height) {
                     currentYMat?.release()
                     yMat = Mat(image.height, image.width, org.opencv.core.CvType.CV_8UC1)
                     reusableByteArray = ByteArray(ySize)
                 }

                 // Copy Y plane (Luminance) which is sufficient for SLAM tracking
                 val buffer = reusableByteArray
                 val mat = yMat

                 if (buffer != null && mat != null) {
                     yPlane.buffer.rewind() // Ensure we read from start
                     yPlane.buffer.get(buffer)
                     mat.put(0, 0, buffer)

                     sphereSLAM?.let { s ->
                         SlamReflectionHelper.processFrame(s, mat.nativeObjAddr, image.timestamp.toDouble(), image.width, image.height, yPlane.rowStride)
                     }
                 }
             } catch (e: Exception) {
                 android.util.Log.e("MappingScreen", "Error processing frame", e)
             } finally {
                 image.close()
             }
        }
        cameraManager = camManager

        onDispose {
            android.util.Log.v("MappingScreen", "Cleaning up SphereSLAM")
            sphereSLAM?.cleanup()
            camManager.closeCamera()
            camManager.stopBackgroundThread()
            thread.quitSafely()
            yMat?.release()
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

                // Update stats occasionally
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
                    cameraManager?.startBackgroundThread()
                    if (hasCameraPermission) {
                        cameraManager?.openCamera()
                    }
                    val handler = sensorThread?.let { Handler(it.looper) }
                    accelerometer?.let { sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_FASTEST, handler) }
                    gyroscope?.let { sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_FASTEST, handler) }
                    Choreographer.getInstance().postFrameCallback(frameCallback)
                }
                Lifecycle.Event.ON_PAUSE -> {
                    android.util.Log.v("MappingScreen", "Lifecycle: ON_PAUSE")
                    Choreographer.getInstance().removeFrameCallback(frameCallback)
                    cameraManager?.closeCamera()
                    cameraManager?.stopBackgroundThread()
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
            navController = null, // Pass null to avoid illegal argument exception when no graph is set
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
                onClick = {
                    android.util.Log.v("MappingScreen", "Button: Back clicked")
                    onExit()
                }
            )

            azRailItem(
                id = "help",
                text = "Help",
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

            // PhotoSphere Creation Overlay (Integrated)
            if (isMapping) {
                 PhotoSphereCreationScreen(
                     onCaptureComplete = {
                         isMapping = false
                         // Save Map Stats
                         try {
                             val projectDir = context.getExternalFilesDir(null)
                             val statsFile = File(projectDir, "scan_data.txt")
                             statsFile.writeText(statsText)

                             val mapFile = File(projectDir, "slam_map.bin")
                             val photoSphereFile = File(projectDir, "photosphere.ppm") // or .png if library handles it

                             // Save Map
                             val mapSaved = sphereSLAM?.let { s -> SlamReflectionHelper.saveMap(s, mapFile.absolutePath) } ?: false

                             // Save Photosphere (Native)
                             val photoSaved = sphereSLAM?.let { s -> SlamReflectionHelper.savePhotosphere(s, photoSphereFile.absolutePath) } ?: false

                             // Fallback Logic for Map
                             if (!mapSaved) {
                                 // Fallback: Copy Cache
                                 val cacheDir = context.cacheDir
                                 if (cacheDir.exists() && cacheDir.isDirectory) {
                                     val mapDir = File(projectDir, "sphereslam_map")
                                     if (!mapDir.exists()) mapDir.mkdirs()
                                     cacheDir.listFiles()?.forEach { file ->
                                         if (file.isFile && !file.name.startsWith("tmp") && !file.name.endsWith(".tmp")) {
                                             file.copyTo(File(mapDir, file.name), overwrite = true)
                                         }
                                     }
                                 }
                             }

                             // Fallback Logic for Photosphere (PixelCopy)
                             if (!photoSaved) {
                                 val view = activity?.window?.decorView
                                 view?.let {
                                     val bitmap = Bitmap.createBitmap(it.width, it.height, Bitmap.Config.ARGB_8888)
                                     val location = IntArray(2)
                                     it.getLocationInWindow(location)
                                     val win = activity.window ?: return@let
                                     android.view.PixelCopy.request(
                                         win,
                                         android.graphics.Rect(location[0], location[1], location[0] + it.width, location[1] + it.height),
                                         bitmap,
                                         { result ->
                                             if (result == android.view.PixelCopy.SUCCESS) {
                                                 val imageFile = File(projectDir, "photosphere_preview.jpg")
                                                 java.io.FileOutputStream(imageFile).use { out ->
                                                     bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                                                 }
                                             }
                                         },
                                         android.os.Handler(android.os.Looper.getMainLooper())
                                     )
                                 }
                             }

                             Toast.makeText(context, "Map & Photosphere Saved", Toast.LENGTH_SHORT).show()
                         } catch (e: Exception) {
                             android.util.Log.e("MappingScreen", "Error saving capture", e)
                             Toast.makeText(context, "Error saving: ${e.message}", Toast.LENGTH_SHORT).show()
                         }
                     },
                     onExit = { isMapping = false }
                 )
            }

            // Instructions Overlay
            if (showInstructions && !isMapping) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 16.dp, start = 16.dp)
                        .background(Color(0x80000000))
                        .padding(16.dp)
                ) {
                    Text(
                        text = "SURVEYOR MODE\n\n1. Press 'Create PhotoSphere' to begin mapping.\n2. Or use 'Load Map' to view previous scans.",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            // Buttons (only if not mapping)
            if (!isMapping) {
                Column(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AzButton(
                        text = "Load Map",
                        shape = AzButtonShape.RECTANGLE,
                        onClick = {
                            loadMapLauncher.launch("*/*")
                        },
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    AzButton(
                        text = "Create PhotoSphere",
                        shape = AzButtonShape.RECTANGLE,
                        onClick = {
                            isMapping = true
                            sphereSLAM?.resetSystem()
                        },
                        modifier = Modifier
                    )
                }
            }
        }
    }
}
