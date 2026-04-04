package com.hereliesaz.graffitixr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.hereliesaz.aznavrail.tutorial.AzHighlight
import com.hereliesaz.aznavrail.tutorial.AzTutorial
import com.hereliesaz.aznavrail.tutorial.azTutorial

@Composable
fun getTutorials(): Map<String, AzTutorial> {
    return mapOf(
        "ar" to azTutorial {
            scene(id = "ar_intro", content = { Box(Modifier.fillMaxSize()) }) {
                card(
                    title = "AR Projection Mode",
                    text = "Project designs using spatial mapping. Scan surroundings until wall is detected, then tap to place.",
                    highlight = AzHighlight.Item("ar")
                )
                card(
                    title = "Exporting Work",
                    text = "Need a high-res file? Use the Export tool to capture your AR scene as a high-quality image.",
                    highlight = AzHighlight.Item("export")
                )
            }
        },
        "overlay" to azTutorial {
            scene(id = "overlay_intro", content = { Box(Modifier.fillMaxSize()) }) {
                card(
                    title = "Overlay Mode",
                    text = "Direct projection onto camera feed for quick mockups.",
                    highlight = AzHighlight.Item("overlay")
                )
            }
        },
        "mockup" to azTutorial {
            scene(id = "mockup_intro", content = { Box(Modifier.fillMaxSize()) }) {
                card(
                    title = "Mockup Mode",
                    text = "Preview design on a photo. Select a wall image, then layer your mural on top.",
                    highlight = AzHighlight.Item("mockup")
                )
            }
        },
        "trace" to azTutorial {
            scene(id = "trace_intro", content = { Box(Modifier.fillMaxSize()) }) {
                card(
                    title = "Lightbox Mode",
                    text = "High-brightness tracing surface. Place paper over the screen and trace.",
                    highlight = AzHighlight.Item("trace")
                )
            }
        },
        "stencil" to azTutorial {
            scene(id = "stencil_intro", content = { Box(Modifier.fillMaxSize()) }) {
                card(
                    title = "Stencil Generation",
                    text = "Transform your sketches into printable templates using advanced stencil filters.",
                    highlight = AzHighlight.Item("stencil") // Assuming 'stencil' exists in helplist
                )
            }
        }
    )
}
