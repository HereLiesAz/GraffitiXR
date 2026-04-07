package com.hereliesaz.graffitixr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.hereliesaz.aznavrail.tutorial.AzHighlight
import com.hereliesaz.aznavrail.tutorial.AzTutorial
import com.hereliesaz.aznavrail.tutorial.azTutorial
import com.hereliesaz.graffitixr.common.model.Layer

@Composable
fun getTutorials(layers: List<Layer>): Map<String, AzTutorial> {
    val tutorials = mutableMapOf<String, AzTutorial>()

    fun simpleTutorial(id: String, title: String, text: String): AzTutorial {
        return azTutorial {
            scene(id = "${id}_intro", content = { Box(Modifier.fillMaxSize()) }) {
                card(title = title, text = text, highlight = AzHighlight.Item(id))
            }
        }
    }

    // --- Mode Menu ---
    tutorials["mode_host"] = simpleTutorial("mode_host", "Mode Switcher", "Switch between app modes. Tap to expand, then select AR, Overlay, Mockup, or Lightbox.")
    tutorials["ar"] = simpleTutorial("ar", "AR Mode", "Project design onto a wall using spatial mapping. Scan environment until wall is detected, then tap to place the anchor.")
    tutorials["overlay"] = simpleTutorial("overlay", "Overlay Mode", "Display design over live camera without spatial tracking. Manually scale and move the design to fit your frame.")
    tutorials["mockup"] = simpleTutorial("mockup", "Mockup Mode", "Preview design on a static photo. Use 'Wall' to set a background image, then arrange layers on top.")
    tutorials["trace"] = simpleTutorial("trace", "Lightbox Mode", "High-brightness tracing surface. Place paper over the screen and trace. Triple-tap anywhere to exit.")

    // --- Target Menu ---
    tutorials["target_host"] = simpleTutorial("target_host", "Target Management", "Tools for AR anchors. Tap to access tools for creating or re-aligning the mural's position.")
    tutorials["scan_mode_toggle"] = simpleTutorial("scan_mode_toggle", "Scan Resolution", "Switch between Mural (large scale) and Canvas (small scale). Mural uses Gaussian Splats for walls; Canvas uses Point Clouds for detailed desk-scale art.")

    tutorials["create"] = azTutorial {
        scene(id = "create_intro", content = { Box(Modifier.fillMaxSize()) }) {
            card(
                title = "Create Anchor",
                text = "Locks your projection to a specific spot on the wall. Once set, the design stays in place even if you walk around.",
                highlight = AzHighlight.Item("create")
            )
            card(
                title = "Surface Requirements",
                text = "The wall needs visible texture — graffiti, rough plaster, brick, or stickers all work well. Smooth, blank, or single-colour walls won't hold an anchor.",
                highlight = AzHighlight.FullScreen
            )
            card(
                title = "What the Blobs Mean",
                text = "After tapping, you'll briefly see green blobs. Those are the tracking keypoints the app extracted. More blobs = stronger lock.",
                highlight = AzHighlight.FullScreen
            )
        }
    }

    // --- Design Menu ---
    tutorials["design_host"] = simpleTutorial("design_host", "Layer Management", "Add images, text, or sketches. Tap to expand. Long-press any layer button to open its secondary menu.")

    tutorials["add_img"] = azTutorial {
        scene(id = "add_img_intro", content = { Box(Modifier.fillMaxSize()) }) {
            card(
                title = "Import Image",
                text = "Add a sketch, photo, or reference from your gallery. It appears as a new layer stacked on top of the others.",
                highlight = AzHighlight.Item("add_img")
            )
            card(
                title = "Scale and Position",
                text = "Pinch with two fingers to resize. Drag with one finger to reposition. The layer snaps to the projected wall surface in AR mode.",
                highlight = AzHighlight.FullScreen
            )
        }
    }

    tutorials["add_draw"] = simpleTutorial("add_draw", "New Sketch", "Add a blank layer for freehand drawing. Select the layer to reveal brush, eraser, and smudge tools.")
    tutorials["add_text"] = simpleTutorial("add_text", "Add Text", "Create a typography layer. Tap the layer's 'Edit' button to change content and formatting.")
    tutorials["wall"] = simpleTutorial("wall", "Change Wall", "Set background for Mockup mode. Select a photo of the physical wall you plan to paint on.")

    // --- Project Menu ---
    tutorials["project_host"] = simpleTutorial("project_host", "Project Management", "Save, load, and export work. Tap to access administrative tasks for your mural.")
    tutorials["new"] = simpleTutorial("new", "New Project", "Start a fresh mural. Tap to reset. Warning: This clears all current work. Save first!")
    tutorials["save"] = simpleTutorial("save", "Save Project", "Store mural and AR map. Enter a project name and tap Save to preserve your progress.")
    tutorials["load"] = simpleTutorial("load", "Project Library", "Access saved murals. Browse the library to resume work or import shared project files.")
    tutorials["export"] = simpleTutorial("export", "Export Image", "Save high-res snapshot. Renders all visible layers into a single PNG in your photo gallery.")
    tutorials["settings"] = simpleTutorial("settings", "App Settings", "Configure preferences. Open to adjust handedness, units, and scanning parameters.")

    // --- Global Tools ---
    tutorials["light"] = simpleTutorial("light", "Flashlight", "Toggle device LED. Use this to illuminate dark walls for better AR tracking.")

    tutorials["lock_trace"] = azTutorial {
        scene(id = "lock_trace_intro", content = { Box(Modifier.fillMaxSize()) }) {
            card(
                title = "Lock / Freeze",
                text = "In Lightbox (Trace) mode: disables touch so you can lay paper on the screen without accidentally moving anything.",
                highlight = AzHighlight.Item("lock_trace")
            )
            card(
                title = "In Other Modes",
                text = "In AR, Overlay, and Mockup modes: locks layer transforms so designs can't be accidentally moved or scaled.",
                highlight = AzHighlight.FullScreen
            )
        }
    }

    tutorials["help_main"] = simpleTutorial("help_main", "Interactive Help", "Explains button functions. Tap to activate (Cyan). While active, tap any button to see its help text.")

    // --- Layers ---
    layers.forEach { layer ->
        val id = layer.id
        tutorials["layer_$id"] = simpleTutorial("layer_$id", "Layer '${layer.name}'", "Active mural element. Tap to select. Drag button up/down to reorder. Transform on screen with gestures.")
        tutorials["edit_text_$id"] = simpleTutorial("edit_text_$id", "Edit Text", "Change words in typography layer. Tap to open the keyboard and update the text content.")
        tutorials["size_$id"] = simpleTutorial("size_$id", "Brush/Text Size", "Adjust scale and softness. Drag up/down for size. Drag left/right for brush feathering.")
        tutorials["font_$id"] = simpleTutorial("font_$id", "Font Picker", "Select typography style. Tap to browse available fonts and apply them to the text layer.")
        tutorials["color_$id"] = simpleTutorial("color_$id", "Color Tool", "Set active color. Drag up/down for brightness, left/right for saturation. Tap for full color wheel.")
        tutorials["kern_$id"] = simpleTutorial("kern_$id", "Text Kerning", "Adjust letter spacing. Drag left/right to tighten or loosen the text layout.")
        tutorials["bold_$id"] = simpleTutorial("bold_$id", "Bold Toggle", "Thick font weight. Tap to toggle bold styling on the active text layer.")
        tutorials["italic_$id"] = simpleTutorial("italic_$id", "Italic Toggle", "Slanted styling. Tap to toggle italic styling on the active text layer.")
        tutorials["outline_$id"] = simpleTutorial("outline_$id", "Text Outline", "Character stroke. Tap to toggle a visible outline around the text characters.")
        tutorials["shadow_$id"] = simpleTutorial("shadow_$id", "Drop Shadow", "Text depth. Tap to toggle a soft shadow behind the typography for better legibility.")

        tutorials["stencil_$id"] = azTutorial {
            scene(id = "stencil_${id}_intro", content = { Box(Modifier.fillMaxSize()) }) {
                card(
                    title = "Generate Stencil",
                    text = "Converts your image into printable spray-paint stencils. Each stencil represents one colour layer of the design.",
                    highlight = AzHighlight.Item("stencil_$id")
                )
                card(
                    title = "How Many Layers?",
                    text = "A 3-colour design produces 3 stencil sheets. More colours = more sheets. Simple high-contrast images give the best results.",
                    highlight = AzHighlight.FullScreen
                )
                card(
                    title = "Exporting",
                    text = "Each stencil exports as a PDF. Print at 100% scale (no scaling) to get accurate life-size dimensions.",
                    highlight = AzHighlight.FullScreen
                )
            }
        }

        tutorials["blend_$id"] = simpleTutorial("blend_$id", "Blend Mode", "Composite style. Tap to cycle through modes like Multiply, Screen, and Overlay.")

        tutorials["adj_$id"] = azTutorial {
            scene(id = "adj_${id}_intro", content = { Box(Modifier.fillMaxSize()) }) {
                card(
                    title = "Image Adjustments",
                    text = "Fine-tune how the layer looks. Tap to reveal sliders for Opacity, Brightness, Contrast, and Saturation.",
                    highlight = AzHighlight.Item("adj_$id")
                )
                card(
                    title = "Opacity & Brightness",
                    text = "Opacity controls how transparent the layer is. Brightness shifts the overall lightness — useful to match the layer to your wall's tone.",
                    highlight = AzHighlight.FullScreen
                )
                card(
                    title = "Contrast & Saturation",
                    text = "Contrast separates darks from lights — great for making stencil outlines pop. Saturation controls colour intensity; drag to zero for greyscale.",
                    highlight = AzHighlight.FullScreen
                )
            }
        }

        tutorials["invert_$id"] = simpleTutorial("invert_$id", "Invert Colors", "High-contrast negative. Tap to flip colors. Whites become black and vice-versa—useful for tracing.")
        tutorials["balance_$id"] = simpleTutorial("balance_$id", "Color Balance", "RGB channel tuning. Tap to reveal sliders for precise Red, Green, and Blue adjustment.")
        tutorials["eraser_$id"] = simpleTutorial("eraser_$id", "Eraser Brush", "Remove layer content. Paint on screen to erase. Use 'Size' button to adjust diameter.")
        tutorials["blur_$id"] = simpleTutorial("blur_$id", "Blur/Smudge Tool", "Blend colors. Paint over regions to soften edges or smudge details together.")
        tutorials["liquify_$id"] = simpleTutorial("liquify_$id", "Warp Tool", "Reshape design. Drag on screen to push and pull pixels—great for adjusting proportions.")
        tutorials["dodge_$id"] = simpleTutorial("dodge_$id", "Dodge Tool", "Lighten areas. Paint over parts of the layer to increase their luminance.")
        tutorials["burn_$id"] = simpleTutorial("burn_$id", "Burn Tool", "Darken areas. Paint over parts of the layer to decrease their luminance.")

        tutorials["iso_$id"] = azTutorial {
            scene(id = "iso_${id}_intro", content = { Box(Modifier.fillMaxSize()) }) {
                card(
                    title = "Isolate Subject",
                    text = "Uses AI to automatically remove the background, leaving only the main subject of the image.",
                    highlight = AzHighlight.Item("iso_$id")
                )
                card(
                    title = "Best Results",
                    text = "Works best on photos with a clear subject against a plain or contrasting background. Complex scenes with busy backgrounds may need manual touch-ups with the eraser.",
                    highlight = AzHighlight.FullScreen
                )
            }
        }

        tutorials["line_$id"] = simpleTutorial("line_$id", "Sketch Outline", "Line art filter. Tap to convert the photo into a black-and-white transparent outline.")
        tutorials["help_layer_$id"] = simpleTutorial("help_layer_$id", "Layer Help", "Toggle info for these specific layer tools. Tap to activate. Then tap any tool icon to see what it does.")
    }

    return tutorials
}
