package com.hereliesaz.graffitixr.composables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun HelpScreen(onGetStarted: () -> Unit) {
    var currentStep by remember { mutableIntStateOf(0) }

    val steps = remember {
        listOf(
            "Welcome" to "Welcome to GraffitiXR!\n\nThis app helps you visualize your artwork in the real world.",
            "Step 1: Choose & Edit" to "Start by choosing an image from your gallery.\n\nUse the tools to remove the background, make it black and white, or increase contrast for better visibility.",
            "Step 2: Create Fingerprint" to "In AR Mode, point your camera at the surface where you want your mural.\n\nTap 'Create Target' to generate a unique fingerprint of the surface.",
            "Step 3: Position & Lock" to "Once the target is created, your image will appear.\n\nRotate and resize it to fit perfectly, then lock it into place to start tracing."
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (currentStep < steps.size) {
            Text(
                text = steps[currentStep].first,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = steps[currentStep].second,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
        } else {
             // Fallback or completion state if needed, though button below handles exit
             Text("Ready to go!", style = MaterialTheme.typography.headlineMedium)
        }

        Spacer(modifier = Modifier.height(32.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (currentStep > 0) {
                Button(onClick = { currentStep-- }) {
                    Text("Back")
                }
            }

            Button(onClick = {
                if (currentStep < steps.size - 1) {
                    currentStep++
                } else {
                    onGetStarted()
                }
            }) {
                Text(if (currentStep < steps.size - 1) "Next" else "Get Started")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Progress indicator
        Text(
            text = "${currentStep + 1} / ${steps.size}",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
