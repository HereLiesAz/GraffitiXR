package com.hereliesaz.graffitixr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.hereliesaz.aznavrail.tutorial.AzHighlight
import com.hereliesaz.aznavrail.tutorial.AzTutorial
import com.hereliesaz.aznavrail.tutorial.azTutorial
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.design.theme.AppStrings

@Composable
fun getTutorials(layers: List<Layer>, strings: AppStrings): Map<String, AzTutorial> {
    val tutorials = mutableMapOf<String, AzTutorial>()

    fun simpleTutorial(id: String, title: String, text: String): AzTutorial {
        return azTutorial {
            scene(id = "${id}_intro", content = { Box(Modifier.fillMaxSize()) }) {
                card(title = title, text = text, highlight = AzHighlight.Item(id))
            }
        }
    }

    // --- Mode Menu ---
    tutorials["mode_host"] = simpleTutorial("mode_host", strings.nav.modes, strings.help.modeHost)
    tutorials["ar"] = simpleTutorial("ar", strings.nav.arMode, strings.help.ar)
    tutorials["overlay"] = simpleTutorial("overlay", strings.nav.overlay, strings.help.overlay)
    tutorials["mockup"] = simpleTutorial("mockup", strings.nav.mockup, strings.help.mockup)
    tutorials["trace"] = simpleTutorial("trace", strings.nav.trace, strings.help.trace)

    // --- Target Menu ---
    tutorials["target_host"] = simpleTutorial("target_host", strings.nav.grid, strings.help.targetHost)
    tutorials["scan_mode_toggle"] = simpleTutorial("scan_mode_toggle", strings.nav.canvas + " / " + strings.nav.mural, strings.help.scanModeToggle)

    tutorials["create"] = azTutorial {
        scene(id = "create_intro", content = { Box(Modifier.fillMaxSize()) }) {
            card(
                title = strings.ar.targetCreationTitle,
                text = strings.ar.targetCreationText,
                highlight = AzHighlight.Item("create")
            )
            card(
                title = strings.ar.planeRealignmentTitle,
                text = strings.ar.planeRealignmentText,
                highlight = AzHighlight.FullScreen
            )
            card(
                title = strings.ar.diagTitle,
                text = strings.ar.diagWaiting,
                highlight = AzHighlight.FullScreen
            )
        }
    }

    // --- Design Menu ---
    tutorials["design_host"] = simpleTutorial("design_host", strings.nav.design, strings.help.designHost)

    tutorials["add_img"] = azTutorial {
        scene(id = "add_img_intro", content = { Box(Modifier.fillMaxSize()) }) {
            card(
                title = strings.help.addImg,
                text = strings.help.addImg,
                highlight = AzHighlight.Item("add_img")
            )
            card(
                title = strings.help.adj,
                text = strings.help.adj,
                highlight = AzHighlight.FullScreen
            )
        }
    }

    tutorials["add_draw"] = simpleTutorial("add_draw", "New Sketch", strings.help.addDraw)
    tutorials["add_text"] = simpleTutorial("add_text", "Add Text", strings.help.addText)
    tutorials["wall"] = simpleTutorial("wall", strings.nav.wall, strings.help.wall)

    // --- Project Menu ---
    tutorials["project_host"] = simpleTutorial("project_host", strings.nav.project, strings.help.projectHost)
    tutorials["new"] = simpleTutorial("new", strings.nav.new, strings.help.newProject)
    tutorials["save"] = simpleTutorial("save", strings.nav.save, strings.help.saveProject)
    tutorials["load"] = simpleTutorial("load", strings.nav.load, strings.help.loadProject)
    tutorials["export"] = simpleTutorial("export", strings.nav.export, strings.help.exportImage)
    tutorials["settings"] = simpleTutorial("settings", strings.nav.settings, strings.help.appSettings)

    // --- Global Tools ---
    tutorials["light"] = simpleTutorial("light", strings.nav.light, strings.help.flashlight)

    tutorials["lock_trace"] = azTutorial {
        scene(id = "lock_trace_intro", content = { Box(Modifier.fillMaxSize()) }) {
            card(
                title = strings.editor.lock + " / " + strings.editor.freeze,
                text = strings.help.lockTrace,
                highlight = AzHighlight.Item("lock_trace")
            )
        }
    }

    tutorials["help_main"] = simpleTutorial("help_main", strings.nav.help, strings.help.helpMain)

    // --- Layers ---
    layers.forEach { layer ->
        val id = layer.id
        val layerNameHelp = strings.help.layer(layer.name)
        
        tutorials["layer_$id"] = simpleTutorial("layer_$id", layerNameHelp, layerNameHelp)
        tutorials["edit_text_$id"] = simpleTutorial("edit_text_$id", "Edit", strings.help.editText)
        tutorials["size_$id"] = simpleTutorial("size_$id", strings.editor.brushSize, strings.help.size)
        tutorials["font_$id"] = simpleTutorial("font_$id", "Font", strings.help.font)
        tutorials["color_$id"] = simpleTutorial("color_$id", "Color Tool", strings.help.color)
        tutorials["kern_$id"] = simpleTutorial("kern_$id", "Text Kerning", strings.help.kern)
        tutorials["bold_$id"] = simpleTutorial("bold_$id", "Bold Toggle", strings.help.bold)
        tutorials["italic_$id"] = simpleTutorial("italic_$id", "Italic Toggle", strings.help.italic)
        tutorials["outline_$id"] = simpleTutorial("outline_$id", "Text Outline", strings.help.outline)
        tutorials["shadow_$id"] = simpleTutorial("shadow_$id", "Drop Shadow", strings.help.shadow)

        tutorials["stencil_$id"] = azTutorial {
            scene(id = "stencil_${id}_intro", content = { Box(Modifier.fillMaxSize()) }) {
                card(
                    title = strings.nav.stencil,
                    text = strings.help.stencilGen,
                    highlight = AzHighlight.Item("stencil_$id")
                )
            }
        }

        tutorials["blend_$id"] = simpleTutorial("blend_$id", "Blend Mode", strings.help.blend)

        tutorials["adj_$id"] = azTutorial {
            scene(id = "adj_${id}_intro", content = { Box(Modifier.fillMaxSize()) }) {
                card(
                    title = strings.nav.adjust,
                    text = strings.help.adj,
                    highlight = AzHighlight.Item("adj_$id")
                )
            }
        }

        tutorials["invert_$id"] = simpleTutorial("invert_$id", strings.nav.invert, strings.help.invert)
        tutorials["balance_$id"] = simpleTutorial("balance_$id", strings.nav.balance, strings.help.balance)
        tutorials["eraser_$id"] = simpleTutorial("eraser_$id", "Eraser Brush", strings.help.eraser)
        tutorials["blur_$id"] = simpleTutorial("blur_$id", "Blur/Smudge Tool", strings.help.blur)
        tutorials["liquify_$id"] = simpleTutorial("liquify_$id", "Warp Tool", strings.help.liquify)
        tutorials["dodge_$id"] = simpleTutorial("dodge_$id", "Dodge Tool", strings.help.dodge)
        tutorials["burn_$id"] = simpleTutorial("burn_$id", "Burn Tool", strings.help.burn)

        tutorials["iso_$id"] = azTutorial {
            scene(id = "iso_${id}_intro", content = { Box(Modifier.fillMaxSize()) }) {
                card(
                    title = strings.nav.isolate,
                    text = strings.help.iso,
                    highlight = AzHighlight.Item("iso_$id")
                )
            }
        }

        tutorials["line_$id"] = simpleTutorial("line_$id", strings.nav.outline, strings.help.line)
        tutorials["help_layer_$id"] = simpleTutorial("help_layer_$id", strings.nav.help, strings.help.helpLayer)
    }

    return tutorials
}
