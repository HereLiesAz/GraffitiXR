package com.hereliesaz.graffitixr

import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hereliesaz.graffitixr.utils.MultiGestureDetector

@Composable
fun ArView(
    viewModel: MainViewModel,
    onRendererCreated: (ArRenderer) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsState()

    // Create Renderer once per Composition
    val renderer = remember { ArRenderer(context) }
    
    // Pass it up immediately
    LaunchedEffect(renderer) {
        onRendererCreated(renderer)
    }

    // Sync UI State to Renderer
    LaunchedEffect(uiState.isFlashlightOn) {
        renderer.setFlashlight(uiState.isFlashlightOn)
    }
    
    LaunchedEffect(uiState.isMappingMode) {
        // Example: If renderer supports mode switching
    }

    // Handle Lifecycle for GLSurfaceView
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                renderer.onResume(context)
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                renderer.onPause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // Note: Cleanup is handled by Activity onDestroy usually, 
            // but if this Composable leaves the screen, we might want to pause.
        }
    }

    AndroidView(
        factory = { ctx ->
            renderer.apply {
                // Setup touch listeners
                val gestureDetector = MultiGestureDetector(ctx, object : MultiGestureDetector.Listener {
                    override fun onDown(e: MotionEvent) {
                        viewModel.onGestureStart()
                    }
                    override fun onUp(e: MotionEvent) {
                        viewModel.onGestureEnd()
                    }
                    // ... map other gestures to viewModel calls ...
                })
                setOnTouchListener { _, event ->
                    gestureDetector.onTouchEvent(event)
                    true // Consume event
                }
            }
        },
        modifier = modifier
    )
}
