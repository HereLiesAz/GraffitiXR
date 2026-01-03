package com.hereliesaz.graffitixr

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.compose.rememberNavController
import com.eqgis.eqr.layout.SceneLayout
import com.hereliesaz.aznavrail.AzNavRail
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.aznavrail.model.AzHeaderIconShape
import com.hereliesaz.graffitixr.slam.SlamManager

@Composable
fun MappingScreen(
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current

    // SLAM Manager State
    // We use remember to keep the reference, but we need to initialize it.
    // Since SlamManager takes an Activity, and we are in a Composable,
    // we should initialize it once we have the activity context.
    var slamManager by remember { mutableStateOf<SlamManager?>(null) }
    var isMapping by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Surveyor Mode: Initialized") }

    // SceneLayout Ref
    var sceneLayoutRef by remember { mutableStateOf<SceneLayout?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (isMapping) {
                        sceneLayoutRef?.resume()
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    sceneLayoutRef?.pause()
                }
                Lifecycle.Event.ON_DESTROY -> {
                    sceneLayoutRef?.destroy()
                    slamManager?.dispose()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Initialize SlamManager once
    if (slamManager == null && activity != null) {
        slamManager = SlamManager(activity)
    }

    val navController = rememberNavController()

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. AR Scene Layer (Background)
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SceneLayout(ctx).apply {
                    try {
                        // SceneLayout requires Activity to init
                        if (ctx is Activity) {
                            init(ctx)
                            sceneLayoutRef = this
                            statusMessage = "Surveyor Mode: Ready"
                        } else {
                            statusMessage = "Error: Context is not Activity"
                        }
                    } catch (e: Exception) {
                        statusMessage = "Error: ${e.message}"
                        android.util.Log.e("MappingScreen", "Error initializing SceneLayout", e)
                    }
                }
            }
        )

        // 2. Nav Rail Layer
        AzNavRail(
            navController = navController,
            currentDestination = "surveyor",
            isLandscape = false // Force rail mode or detect configuration
        ) {
            azSettings(
                displayAppNameInHeader = true,
                headerIconShape = AzHeaderIconShape.ROUNDED
            )

            azRailItem(
                id = "back",
                text = "Back",
                route = "back",
                onClick = onExit
            )
        }

        // 3. UI Overlay Layer (Status & Buttons)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 80.dp) // Offset for Rail
        ) {
            // Status Text
            Text(
                text = statusMessage,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
                    .background(Color(0x80000000))
                    .padding(8.dp),
                style = MaterialTheme.typography.bodyMedium
            )

            // Control Buttons
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                // Start Button
                com.hereliesaz.aznavrail.AzButton(
                    text = "Start",
                    shape = AzButtonShape.RECTANGLE,
                    onClick = {
                        if (!isMapping) {
                            try {
                                if (slamManager == null && activity != null) {
                                    slamManager = SlamManager(activity)
                                }
                                slamManager?.init()
                                sceneLayoutRef?.resume()
                                isMapping = true
                                statusMessage = "Mapping Started"
                                Toast.makeText(context, "Mapping Started", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                statusMessage = "Error starting: ${e.message}"
                                android.util.Log.e("MappingScreen", "Start Error", e)
                            }
                        }
                    },
                    modifier = Modifier.padding(end = 8.dp)
                )

                // Stop Button
                com.hereliesaz.aznavrail.AzButton(
                    text = "Stop",
                    shape = AzButtonShape.RECTANGLE,
                    onClick = {
                        if (isMapping) {
                            sceneLayoutRef?.pause()
                            isMapping = false
                            statusMessage = "Mapping Stopped"
                            Toast.makeText(context, "Mapping Stopped", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.padding(end = 8.dp)
                )

                // Save Button
                com.hereliesaz.aznavrail.AzButton(
                    text = "Save",
                    shape = AzButtonShape.RECTANGLE,
                    onClick = {
                        slamManager?.saveMap()
                        Toast.makeText(context, "Map Save Requested", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }
}
