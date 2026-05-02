package com.hereliesaz.graffitixr.ui.coop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun CoopSpectatorBanner(
    peerName: String,
    isReconnecting: Boolean,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (isReconnecting) "Reconnecting to $peerName…" else "Spectating $peerName",
            color = Color.White,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = onLeave) { Text("Leave") }
    }
}
