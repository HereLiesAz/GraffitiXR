package com.hereliesaz.graffitixr

import android.app.Activity
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape

@Composable
fun PhotoSphereCreationScreen(
    onCaptureComplete: () -> Unit,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity

    // Placeholder for instructions
    Box(modifier = Modifier.fillMaxSize()) {

        // This is where the SphereSLAM SurfaceView will go (passed from parent or integrated here)
        // For now, we assume the parent (MappingScreen) handles the View.

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .background(Color(0x80000000))
                .padding(16.dp)
        ) {
            Text(
                text = "Rotate slowly to capture the environment.\nCover all angles.\nSystem will auto-capture keyframes.",
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge
            )
        }

        AzButton(
            text = "Cancel",
            shape = AzButtonShape.RECTANGLE,
            onClick = onExit,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp)
        )

        AzButton(
            text = "Finish Capture",
            shape = AzButtonShape.RECTANGLE,
            onClick = onCaptureComplete,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)
        )
    }
}
