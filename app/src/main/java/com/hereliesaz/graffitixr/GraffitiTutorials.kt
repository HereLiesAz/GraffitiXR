package com.hereliesaz.graffitixr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.hereliesaz.aznavrail.tutorial.AzHighlight
import com.hereliesaz.aznavrail.tutorial.AzTutorial
import com.hereliesaz.aznavrail.tutorial.azTutorial

fun getGraffitiTutorials(): Map<String, AzTutorial> {
    return mapOf(
        "ar_mode" to azTutorial {
            scene(
                id = "ar_scene",
                content = { Box(Modifier.fillMaxSize()) }
            ) {
                card(
                    title = "AR Projection",
                    text = "Use this mode to project your designs onto real walls. Scan until the app detects a surface, then tap to place your mural.",
                    highlight = AzHighlight.Item("ar")
                )
            }
        },
        "overlay_mode" to azTutorial {
            scene(
                id = "overlay_scene",
                content = { Box(Modifier.fillMaxSize()) }
            ) {
                card(
                    title = "Overlay Mode",
                    text = "Project designs directly onto the live camera view without needing surface detection.",
                    highlight = AzHighlight.Item("overlay")
                )
            }
        },
        "mockup_mode" to azTutorial {
            scene(
                id = "mockup_scene",
                content = { Box(Modifier.fillMaxSize()) }
            ) {
                card(
                    title = "Mockup Mode",
                    text = "Preview your design on a photo of a wall. Add a background image and layer your mural on top.",
                    highlight = AzHighlight.Item("mockup")
                )
            }
        },
        "trace_mode" to azTutorial {
            scene(
                id = "trace_scene",
                content = { Box(Modifier.fillMaxSize()) }
            ) {
                card(
                    title = "Lightbox Mode",
                    text = "Turn your screen into a high-brightness light box. Lay paper over your screen and trace your design.",
                    highlight = AzHighlight.Item("trace")
                )
            }
        }
    )
}
