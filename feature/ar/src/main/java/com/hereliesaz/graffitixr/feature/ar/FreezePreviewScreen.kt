// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/FreezePreviewScreen.kt
package com.hereliesaz.graffitixr.feature.ar

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Fullscreen overlay shown after the user taps Freeze.
 * Displays the annotated composite (wall + artwork + ORB feature blobs) so the
 * user can see exactly what the teleological SLAM engine will match against.
 *
 * [onDismiss] — user tapped "Got it", stays frozen.
 * [onUnfreeze] — user tapped "Unfreeze", undo layer lock and return to editing.
 */
@Composable
fun FreezePreviewScreen(
    annotatedBitmap: Bitmap,
    showDepthWarning: Boolean,
    onDismiss: () -> Unit,
    onUnfreeze: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {

        // Full-screen annotated image
        Image(
            bitmap = annotatedBitmap.asImageBitmap(),
            contentDescription = "Teleological SLAM target",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )

        // Dark scrim so blobs pop
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
        )

        // Re-draw image over scrim
        Image(
            bitmap = annotatedBitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 24.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Teleological SLAM Target",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Green blobs are the features the engine will match against in real time.",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )

            if (showDepthWarning) {
                Box(
                    modifier = Modifier
                        .background(Color(0xEECC4400.toInt()), RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "No depth data from target capture — teleological correction may be reduced.",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(32.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            AzButton(
                text = "Unfreeze",
                onClick = onUnfreeze,
                color = MaterialTheme.colorScheme.error,
                shape = AzButtonShape.CIRCLE
            )
            AzButton(
                text = "Got it",
                onClick = onDismiss,
                shape = AzButtonShape.CIRCLE
            )
        }
    }
}
