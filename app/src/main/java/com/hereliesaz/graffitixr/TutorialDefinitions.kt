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
                    text = "Project your designs onto walls using spatial mapping. Scan your surroundings until a wall is detected, then tap to place your design.",
                    highlight = AzHighlight.Item("ar")
                )
            }
        },
        "overlay" to azTutorial {
            scene(id = "overlay_intro", content = { Box(Modifier.fillMaxSize()) }) {
                card(
                    title = "Overlay Mode",
                    text = "Project your design directly onto your camera feed. Perfect for quick mockups without spatial mapping.",
                    highlight = AzHighlight.Item("overlay")
                )
            }
        },
        "mockup" to azTutorial {
            scene(id = "mockup_intro", content = { Box(Modifier.fillMaxSize()) }) {
                card(
                    title = "Mockup Mode",
                    text = "See your design on a static photo. Select a wall image and layer your mural on top.",
                    highlight = AzHighlight.Item("mockup")
                )
            }
        },
        "trace" to azTutorial {
            scene(id = "trace_intro", content = { Box(Modifier.fillMaxSize()) }) {
                card(
                    title = "Lightbox Mode",
                    text = "Turn your phone into a high-brightness light source for tracing designs onto paper.",
                    highlight = AzHighlight.Item("trace")
                )
            }
        }
    )
}
