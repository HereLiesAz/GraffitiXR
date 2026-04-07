package com.hereliesaz.graffitixr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
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
                    text = "Projects your design onto a real wall using the phone's depth sensor and spatial tracking.",
                    highlight = AzHighlight.Item("ar")
                )
                card(
                    title = "Step 1: Scan",
                    text = "Point at the wall and move slowly side-to-side. Coloured splats appear as the engine maps the surface. Wait until splats cover the wall area.",
                    highlight = AzHighlight.FullScreen
                )
                card(
                    title = "Step 2: Tap to Place",
                    text = "Just tap the screen. The app photographs the wall, extracts tracking features, and locks the anchor instantly.",
                    highlight = AzHighlight.FullScreen
                )
                card(
                    title = "Tip: Texture Matters",
                    text = "Blank white walls, smooth concrete, and single-colour surfaces won't track. Aim your anchor point at graffiti, rough plaster, or any distinct mark.",
                    highlight = AzHighlight.FullScreen
                )
            }
        },
        "overlay_mode" to azTutorial {
            scene(
                id = "overlay_scene",
                content = { Box(Modifier.fillMaxSize()) }
            ) {
                card(
                    title = "Live Overlay",
                    text = "Your design floats over the live camera view — no surface scanning needed.",
                    highlight = AzHighlight.Item("overlay")
                )
                card(
                    title = "Add Your Artwork",
                    text = "Tap Design → Image to import a photo or sketch from your gallery. It appears as a layer on top of the camera feed.",
                    highlight = AzHighlight.FullScreen
                )
                card(
                    title = "Position It",
                    text = "Pinch to scale, drag to reposition. The overlay stays fixed to your screen — step back to see how it fits the wall.",
                    highlight = AzHighlight.FullScreen
                )
            }
        },
        "mockup_mode" to azTutorial {
            scene(
                id = "mockup_scene",
                content = { Box(Modifier.fillMaxSize()) }
            ) {
                card(
                    title = "Photo Mockup",
                    text = "Preview your design on a photo before you ever pick up a brush.",
                    highlight = AzHighlight.Item("mockup")
                )
                card(
                    title = "Step 1: Add the Wall",
                    text = "Tap Design → Wall to import a photo of the wall you plan to paint. This becomes the background.",
                    highlight = AzHighlight.FullScreen
                )
                card(
                    title = "Step 2: Add Your Design",
                    text = "Tap Design → Image to add your artwork on top. Pinch and drag to size and position it on the wall photo.",
                    highlight = AzHighlight.FullScreen
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
                    text = "Turns your screen into a bright lightbox for tracing your design onto paper.",
                    highlight = AzHighlight.Item("trace")
                )
                card(
                    title = "How to Use It",
                    text = "Lay a sheet of paper directly over the screen. Trace the design with a pencil or pen. Use the Image layer to set what gets displayed.",
                    highlight = AzHighlight.FullScreen
                )
                card(
                    title = "Exiting Lightbox",
                    text = "Triple-tap anywhere on the screen to exit. Screen brightness is controlled by your device's display brightness slider.",
                    highlight = AzHighlight.FullScreen
                )
            }
        }
    )
}
