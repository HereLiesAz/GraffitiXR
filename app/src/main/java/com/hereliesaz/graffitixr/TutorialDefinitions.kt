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

    // Helper to generate a simple single-scene tutorial
    fun simpleTutorial(id: String, title: String, text: String): AzTutorial {
        return azTutorial {
            scene(id = "${id}_intro", content = { Box(Modifier.fillMaxSize()) }) {
                card(
                    title = title,
                    text = text,
                    highlight = AzHighlight.Item(id)
                )
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
    tutorials["create"] = simpleTutorial("create", "Create Anchor", "Photograph a mark on the wall to lock projection. Point at a distinct texture and tap. The app will track this mark.")

    // --- Design Menu ---
    tutorials["design_host"] = simpleTutorial("design_host", "Layer Management", "Add images, text, or sketches. Tap to expand. Long-press any layer button to open its secondary menu.")
    tutorials["add_img"] = simpleTutorial("add_img", "Import Image", "Add a sketch or reference photo. Select an image from your gallery. It will appear as a new design layer.")
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
    tutorials["lock_trace"] = simpleTutorial("lock_trace", "Lock/Freeze", "Prevent accidental changes. In Trace mode, disables screen. In other modes, locks layer transforms.")
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
        tutorials["stencil_$id"] = simpleTutorial("stencil_$id", "Generate Stencil", "Create printable templates. Tap to begin multi-layer stencil generation for this image.")
        tutorials["blend_$id"] = simpleTutorial("blend_$id", "Blend Mode", "Composite style. Tap to cycle through modes like Multiply, Screen, and Overlay.")
        tutorials["adj_$id"] = simpleTutorial("adj_$id", "Image Adjustments", "Fine-tune appearance. Tap to reveal sliders for Opacity, Brightness, Contrast, and Saturation.")
        tutorials["invert_$id"] = simpleTutorial("invert_$id", "Invert Colors", "High-contrast negative. Tap to flip colors. Whites become black and vice-versa—useful for tracing.")
        tutorials["balance_$id"] = simpleTutorial("balance_$id", "Color Balance", "RGB channel tuning. Tap to reveal sliders for precise Red, Green, and Blue adjustment.")
        tutorials["eraser_$id"] = simpleTutorial("eraser_$id", "Eraser Brush", "Remove layer content. Paint on screen to erase. Use 'Size' button to adjust diameter.")
        tutorials["blur_$id"] = simpleTutorial("blur_$id", "Blur/Smudge Tool", "Blend colors. Paint over regions to soften edges or smudge details together.")
        tutorials["liquify_$id"] = simpleTutorial("liquify_$id", "Warp Tool", "Reshape design. Drag on screen to push and pull pixels—great for adjusting proportions.")
        tutorials["dodge_$id"] = simpleTutorial("dodge_$id", "Dodge Tool", "Lighten areas. Paint over parts of the layer to increase their luminance.")
        tutorials["burn_$id"] = simpleTutorial("burn_$id", "Burn Tool", "Darken areas. Paint over parts of the layer to decrease their luminance.")
        tutorials["iso_$id"] = simpleTutorial("iso_$id", "Isolate Subject", "Auto-background removal. Tap to extract the main subject from its background using AI.")
        tutorials["line_$id"] = simpleTutorial("line_$id", "Sketch Outline", "Line art filter. Tap to convert the photo into a black-and-white transparent outline.")
        tutorials["help_layer_$id"] = simpleTutorial("help_layer_$id", "Layer Help", "Toggle info for these specific layer tools. Tap to activate. Then tap any tool icon to see what it does.")
    }

    return tutorials
}
