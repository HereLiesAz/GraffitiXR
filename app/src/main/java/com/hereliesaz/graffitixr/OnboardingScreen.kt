package com.hereliesaz.graffitixr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * A composable function that displays a one-time onboarding screen to new users.
 *
 * This screen provides a brief explanation of the three main modes of the application
 * to ensure users understand the core functionalities before they begin.
 *
 * @param onOnboardingComplete A callback lambda that is invoked when the user clicks the
 *   "Get Started" button, signaling that the onboarding flow is finished.
 */
@Composable
fun OnboardingScreen(onOnboardingComplete: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Welcome to GraffitiXR!",
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "This app has three main modes to help you visualize your artwork:",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))

            ModeDescription(
                title = "AR Mode",
                description = "Use your camera to project your art onto any real-world surface. Perfect for seeing your mural on the actual wall."
            )
            Spacer(modifier = Modifier.height(16.dp))

            ModeDescription(
                title = "Mock-up Mode",
                description = "Got a photo of a spot? Overlay your art on a static image and adjust the perspective to match."
            )
            Spacer(modifier = Modifier.height(16.dp))

            ModeDescription(
                title = "On-the-Go Mode",
                description = "A simple camera overlay for quick mock-ups without AR tracking. Just point and shoot."
            )
            Spacer(modifier = Modifier.height(48.dp))

            Button(onClick = onOnboardingComplete) {
                Text("Get Started")
            }
        }
    }
}

/**
 * A helper composable to display the title and description for a single mode.
 */
@Composable
private fun ModeDescription(title: String, description: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = title, style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
    }
}