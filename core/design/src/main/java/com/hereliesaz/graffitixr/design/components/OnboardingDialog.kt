package com.hereliesaz.graffitixr.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.graffitixr.common.model.EditorMode

/**
 * Displays contextual help based on the current active EditorMode.
 * Requires user to tap anywhere to advance through multiple steps or close at the end.
 */
@Composable
fun OnboardingDialog(
    mode: EditorMode,
    onDismiss: () -> Unit
) {
    val title = when (mode) {
        EditorMode.AR -> "AR Mode"
        EditorMode.TRACE -> "Trace Mode"
        EditorMode.MOCKUP -> "Mockup Mode"
        EditorMode.OVERLAY -> "Overlay Mode"
        EditorMode.STENCIL -> "Stencil Mode"
    }

    val steps = when (mode) {
        EditorMode.AR -> listOf(
            "Virtually project images onto real-world surfaces.",
            "Ensure the area is well lit and scan the floor and walls slowly.",
            "Once a solid mesh appears, tap 'Create' to begin capturing your surface."
        )
        EditorMode.TRACE -> listOf(
            "Project images onto surfaces to trace them in real space.",
            "Import an image using the Design menu.",
            "Use the lock button to freeze the image in place while you trace."
        )
        EditorMode.MOCKUP -> listOf(
            "Visualize your artwork on 3D surfaces with perspective.",
            "Select a background image or take a photo of a wall.",
            "Place and adjust your layers to see how they will look in reality."
        )
        EditorMode.OVERLAY -> listOf(
            "Compare your progress with a semi-transparent reference.",
            "Add your reference image.",
            "Adjust its opacity to match your real-world painting."
        )
        EditorMode.STENCIL -> listOf(
            "Generate printable multi-layer stencils from your artwork.",
            "Isolate the colors you need.",
            "Export the separated layers to print."
        )
    }

    var currentStep by remember { mutableStateOf(0) }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .padding(32.dp)
                .background(Color(0xEE222222), RoundedCornerShape(16.dp))
                .border(1.dp, Color.Cyan.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) {
                    if (currentStep < steps.size - 1) {
                        currentStep++
                    } else {
                        onDismiss()
                    }
                }
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = title,
                color = Color.Cyan,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                text = steps[currentStep],
                color = Color.White,
                fontSize = 18.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                steps.forEachIndexed { index, _ ->
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                if (index == currentStep) Color.Cyan else Color.Gray,
                                RoundedCornerShape(4.dp)
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (currentStep < steps.size - 1) "Tap card to continue" else "Tap card to finish",
                color = Color.Gray,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}
