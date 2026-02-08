package com.hereliesaz.graffitixr.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun OnboardingScreen(onDismiss: () -> Unit) {
    val columnModifier = Modifier
        .fillMaxSize()
        .padding(16.dp)
        .padding(top = 100.dp)

    val headlineStyle = MaterialTheme.typography.headlineMedium
    val bodyStyle = MaterialTheme.typography.bodyLarge

    androidx.compose.foundation.layout.Column(
        modifier = columnModifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Welcome to GraffitiXR",
            style = headlineStyle,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Visualize your artwork in the real world.\n\n1. Point your camera at a surface.\n2. Tap to create a target.\n3. Select an image to project.",
            style = bodyStyle,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onDismiss) {
            Text("Get Started")
        }
    }
}
