package com.hereliesaz.graffitixr.ui.coop

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale

/**
 * The tripod's view, full screen: the host's composited AR frame (camera plus design) as it streams
 * in. [frame] is null until the first one arrives; [waiting] is shown meanwhile. Fit, not crop, so
 * nothing the camera sees is hidden behind the screen edge.
 */
@Composable
fun CoopTripodView(frame: Bitmap?, waiting: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        if (frame != null) {
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(text = waiting, color = Color.White)
        }
    }
}
