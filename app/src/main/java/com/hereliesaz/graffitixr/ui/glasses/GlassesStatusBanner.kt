package com.hereliesaz.graffitixr.ui.glasses

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun GlassesStatusBanner(
    isFallback: Boolean,
    fallbackReason: String?,
    onReconnect: () -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (isFallback) Color(0xFFB94B4B) else Color.Black.copy(alpha = 0.7f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (isFallback) {
                "Glasses disconnected — phone-only mode (${fallbackReason.orEmpty()})"
            } else {
                "Glasses active"
            },
            color = Color.White,
            modifier = Modifier.weight(1f),
        )
        if (isFallback) {
            Button(onClick = onReconnect) { Text("Reconnect") }
        } else {
            Button(onClick = onLeave) { Text("End") }
        }
    }
}
