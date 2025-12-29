package com.hereliesaz.graffitixr.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun HelpScreen() {
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val topSpacerHeight = screenHeight * 0.2f // Top 20%

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(topSpacerHeight))

            Text(
                text = "Help & Information",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            HelpItem(
                title = "AR Mode",
                description = "In this mode, you can use Augmented Reality to place your artwork in the real world. First, you'll need to create an image target by pointing your camera at a real-world object."
            )

            HelpItem(
                title = "Overlay Mode",
                description = "In this mode, you can overlay your artwork on the live camera feed. This is a great way to get a quick preview of your work in the real world."
            )

            HelpItem(
                title = "Mockup Mode",
                description = "In this mode, you can mockup your artwork on a static background image. Use the controls to adjust the size, position, and orientation of your artwork."
            )

            HelpItem(
                title = "Trace Mode",
                description = "In this mode, your device acts as a lightbox. Place a piece of paper over the screen to trace your artwork. You can lock the screen to prevent accidental touches."
            )

            Spacer(modifier = Modifier.height(100.dp)) // Bottom padding
        }
    }
}

@Composable
private fun HelpItem(title: String, description: String) {
    Box(
        modifier = Modifier
            .padding(bottom = 24.dp) // Spacing between items
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.1f))
            .padding(24.dp)
    ) {
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.9f)
            )
        }
    }
}
