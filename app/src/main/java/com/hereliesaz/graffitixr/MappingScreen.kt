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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
    var slamManager by remember { mutableStateOf<SlamManager?>(null) }
    var isMapping by remember { mutableStateOf(false) }
    var showInstructions by remember { mutableStateOf(true) }

    // UI Messages
    val initialInstruction = "1. Press 'Start' to begin surveying."
    val mappingInstruction = "2. Move device slowly to scan area.\n   Press 'Stop' when finished."
    val saveInstruction = "3. Press 'Save' to store the map."

    var currentInstruction by remember { mutableStateOf(initialInstruction) }

    // SceneLayout Ref
    var sceneLayoutRef by remember { mutableStateOf<SceneLayout?>(null) }

    // Initialize SlamManager once
    if (slamManager == null && activity != null) {
        slamManager = SlamManager(activity)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    // Always resume the SceneLayout to show camera feed
                    sceneLayoutRef?.resume()
                    slamManager?.resume()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    sceneLayoutRef?.pause()
                    slamManager?.pause()
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

    val navController = rememberNavController()

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. AR Scene Layer (Background)
        // We place this first so it is at the bottom of the Z-stack.
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SceneLayout(ctx).apply {
                    try {
                        if (ctx is Activity) {
                            init(ctx)
                            // Force resume immediately to start camera feed.
                            // The LifecycleObserver might miss the initial ON_RESUME if added too late.
                            this.resume()
                            sceneLayoutRef = this
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("MappingScreen", "Error initializing SceneLayout", e)
                    }
                }
            }
        )

        // 2. Nav Rail Layer
        // Placed second to float above the camera feed.
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
                onClick = onExit
            )

            azRailItem(
                id = "help",
                text = "Help",
                route = "help",
                onClick = { showInstructions = !showInstructions }
            )
        }

        // 3. UI Overlay Layer (Instructions & Buttons)
        // Placed last to float above everything (though rail handles its own z-index usually)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 80.dp) // Offset for Rail
        ) {
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
                        text = "SURVEYOR MODE\n\n$currentInstruction",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            // Control Buttons
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 96.dp),
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
                                isMapping = true
                                currentInstruction = mappingInstruction
                                Toast.makeText(context, "Mapping Started", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                android.util.Log.e("MappingScreen", "Start Error", e)
                                Toast.makeText(context, "Start Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier.padding(end = 16.dp)
                )

                // Stop Button
                com.hereliesaz.aznavrail.AzButton(
                    text = "Stop",
                    shape = AzButtonShape.RECTANGLE,
                    onClick = {
                        if (isMapping) {
                            // slamManager?.stop() // SlamManager doesn't have stop(), maybe dispose() or pause()?
                            isMapping = false
                            currentInstruction = saveInstruction
                            Toast.makeText(context, "Mapping Stopped", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.padding(end = 16.dp)
                )

                // Save Button
                com.hereliesaz.aznavrail.AzButton(
                    text = "Save",
                    shape = AzButtonShape.RECTANGLE,
                    onClick = {
                        try {
                            slamManager?.saveMap()
                            currentInstruction = "Map Saved!\n$initialInstruction"
                            Toast.makeText(context, "Map Save Requested", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            android.util.Log.e("MappingScreen", "Save Error", e)
                            Toast.makeText(context, "Save Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }
    }
}