package com.hereliesaz.graffitixr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.hereliesaz.aznavrail.tutorial.AzHighlight
import com.hereliesaz.aznavrail.tutorial.AzTutorial
import com.hereliesaz.aznavrail.tutorial.azTutorial
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.design.theme.AppStrings

@Composable
fun getTutorials(layers: List<Layer>, strings: AppStrings): Map<String, AzTutorial> {
    val tutorials = mutableMapOf<String, AzTutorial>()

    @Composable
    fun resolve(any: Any): String = when (any) {
        is Int -> stringResource(any)
        is String -> any
        else -> any.toString()
    }

    fun createSimpleTutorial(id: String, title: String, text: String): AzTutorial {
        return azTutorial {
            scene(id = "${id}_intro", content = { Box(Modifier.fillMaxSize()) }) {
                card(title = title, text = text, highlight = AzHighlight.Item(id))
            }
        }
    }

    // Resolve all static strings
    val navModes = resolve(strings.nav.modes)
    val helpModeHost = resolve(strings.help.modeHost)
    val navArMode = resolve(strings.nav.arMode)
    val helpAr = resolve(strings.help.ar)
    val navOverlay = resolve(strings.nav.overlay)
    val helpOverlay = resolve(strings.help.overlay)
    val navMockup = resolve(strings.nav.mockup)
    val helpMockup = resolve(strings.help.mockup)
    val navTrace = resolve(strings.nav.trace)
    val helpTrace = resolve(strings.help.trace)
    val navGrid = resolve(strings.nav.grid)
    val helpTargetHost = resolve(strings.help.targetHost)
    val navCanvas = resolve(strings.nav.canvas)
    val navMural = resolve(strings.nav.mural)
    val helpScanModeToggle = resolve(strings.help.scanModeToggle)
    val navDesign = resolve(strings.nav.design)
    val helpDesignHost = resolve(strings.help.designHost)
    val helpAddImg = resolve(strings.help.addImg)
    val helpAdj = resolve(strings.help.adj)
    val navWall = resolve(strings.nav.wall)
    val helpWall = resolve(strings.help.wall)
    val navProject = resolve(strings.nav.project)
    val helpProjectHost = resolve(strings.help.projectHost)
    val navNew = resolve(strings.nav.new)
    val helpNewProject = resolve(strings.help.newProject)
    val navSave = resolve(strings.nav.save)
    val helpSaveProject = resolve(strings.help.saveProject)
    val navLoad = resolve(strings.nav.load)
    val helpLoadProject = resolve(strings.help.loadProject)
    val navExport = resolve(strings.nav.export)
    val helpExportImage = resolve(strings.help.exportImage)
    val navSettings = resolve(strings.nav.settings)
    val helpAppSettings = resolve(strings.help.appSettings)
    val navLight = resolve(strings.nav.light)
    val helpFlashlight = resolve(strings.help.flashlight)
    val helpLockTrace = resolve(strings.help.lockTrace)
    val navHelp = resolve(strings.nav.help)
    val helpHelpMain = resolve(strings.help.helpMain)
    
    // Layer tool strings
    val navEdit = resolve(strings.nav.edit)
    val helpEditText = resolve(strings.help.editText)
    val helpSize = resolve(strings.help.size)
    val navFont = resolve(strings.nav.font)
    val helpFont = resolve(strings.help.font)
    val navColor = resolve(strings.nav.color)
    val helpColor = resolve(strings.help.color)
    val navKern = resolve(strings.nav.kern)
    val helpKern = resolve(strings.help.kern)
    val navBold = resolve(strings.nav.bold)
    val helpBold = resolve(strings.help.bold)
    val navItalic = resolve(strings.nav.italic)
    val helpItalic = resolve(strings.help.italic)
    val navOutline = resolve(strings.nav.outline)
    val helpOutline = resolve(strings.help.outline)
    val navShadow = resolve(strings.nav.shadow)
    val helpShadow = resolve(strings.help.shadow)
    val navStencil = resolve(strings.nav.stencil)
    val helpStencilGen = resolve(strings.help.stencilGen)
    val helpBlend = resolve(strings.help.blend)
    val navBuild = resolve(strings.nav.build)
    val navAdjust = resolve(strings.nav.adjust)
    val navInvert = resolve(strings.nav.invert)
    val helpInvert = resolve(strings.help.invert)
    val navBalance = resolve(strings.nav.balance)
    val helpBalance = resolve(strings.help.balance)
    val navEraser = resolve(strings.nav.eraser)
    val helpEraser = resolve(strings.help.eraser)
    val navBlur = resolve(strings.nav.blur)
    val helpBlur = resolve(strings.help.blur)
    val navLiquify = resolve(strings.nav.liquify)
    val helpLiquify = resolve(strings.help.liquify)
    val navDodge = resolve(strings.nav.dodge)
    val helpDodge = resolve(strings.help.dodge)
    val navBurn = resolve(strings.nav.burn)
    val helpBurn = resolve(strings.help.burn)
    val navIsolate = resolve(strings.nav.isolate)
    val helpIso = resolve(strings.help.iso)
    val helpLine = resolve(strings.help.line)
    val helpHelpLayer = resolve(strings.help.helpLayer)

    // --- Mode Menu ---
    tutorials["mode_host"] = createSimpleTutorial("mode_host", navModes, helpModeHost)
    tutorials["ar"] = createSimpleTutorial("ar", navArMode, helpAr)
    tutorials["overlay"] = createSimpleTutorial("overlay", navOverlay, helpOverlay)
    tutorials["mockup"] = createSimpleTutorial("mockup", navMockup, helpMockup)
    tutorials["trace"] = createSimpleTutorial("trace", navTrace, helpTrace)

    // --- Target Menu ---
    tutorials["target_host"] = createSimpleTutorial("target_host", navGrid, helpTargetHost)
    tutorials["scan_mode_toggle"] = createSimpleTutorial("scan_mode_toggle", "$navCanvas / $navMural", helpScanModeToggle)

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
                highlight = AzHighlight.FullScreen,
                actionText = strings.common.done
            )
        }
    }

    // --- Design Menu ---
    tutorials["design_host"] = createSimpleTutorial("design_host", navDesign, helpDesignHost)

    tutorials["add_img"] = azTutorial {
        scene(id = "add_img_intro", content = { Box(Modifier.fillMaxSize()) }) {
            card(
                title = helpAddImg,
                text = helpAddImg,
                highlight = AzHighlight.Item("add_img")
            )
            card(
                title = helpAdj,
                text = helpAdj,
                highlight = AzHighlight.FullScreen
            )
        }
    }

    tutorials["add_draw"] = createSimpleTutorial("add_draw", resolve(strings.nav.draw), resolve(strings.help.addDraw))
    tutorials["add_text"] = createSimpleTutorial("add_text", resolve(strings.nav.text), resolve(strings.help.addText))
    tutorials["wall"] = createSimpleTutorial("wall", navWall, helpWall)

    // --- Project Menu ---
    tutorials["project_host"] = createSimpleTutorial("project_host", navProject, helpProjectHost)
    tutorials["new"] = createSimpleTutorial("new", navNew, helpNewProject)
    tutorials["save"] = createSimpleTutorial("save", navSave, helpSaveProject)
    tutorials["load"] = createSimpleTutorial("load", navLoad, helpLoadProject)
    tutorials["export"] = createSimpleTutorial("export", navExport, helpExportImage)
    tutorials["settings"] = createSimpleTutorial("settings", navSettings, helpAppSettings)

    // --- Global Tools ---
    tutorials["light"] = createSimpleTutorial("light", navLight, helpFlashlight)

    tutorials["lock_trace"] = azTutorial {
        scene(id = "lock_trace_intro", content = { Box(Modifier.fillMaxSize()) }) {
            card(
                title = strings.editor.lock + " / " + strings.editor.freeze,
                text = helpLockTrace,
                highlight = AzHighlight.Item("lock_trace")
            )
        }
    }

    tutorials["help_main"] = createSimpleTutorial("help_main", navHelp, helpHelpMain)

    // --- Layers ---
    layers.forEach { layer ->
        val id = layer.id
        val layerNameHelp = strings.help.layer(layer.name)
        
        tutorials["layer_$id"] = createSimpleTutorial("layer_$id", layerNameHelp, layerNameHelp)
        tutorials["edit_text_$id"] = createSimpleTutorial("edit_text_$id", navEdit, helpEditText)
        tutorials["size_$id"] = createSimpleTutorial("size_$id", strings.editor.brushSize, helpSize)
        tutorials["font_$id"] = createSimpleTutorial("font_$id", navFont, helpFont)
        tutorials["color_$id"] = createSimpleTutorial("color_$id", navColor, helpColor)
        tutorials["kern_$id"] = createSimpleTutorial("kern_$id", navKern, helpKern)
        tutorials["bold_$id"] = createSimpleTutorial("bold_$id", navBold, helpBold)
        tutorials["italic_$id"] = createSimpleTutorial("italic_$id", navItalic, helpItalic)
        tutorials["outline_$id"] = createSimpleTutorial("outline_$id", navOutline, helpOutline)
        tutorials["shadow_$id"] = createSimpleTutorial("shadow_$id", navShadow, helpShadow)

        tutorials["stencil_$id"] = azTutorial {
            scene(id = "stencil_${id}_intro", content = { Box(Modifier.fillMaxSize()) }) {
                card(
                    title = navStencil,
                    text = helpStencilGen,
                    highlight = AzHighlight.Item("stencil_$id")
                )
            }
        }

        tutorials["blend_$id"] = createSimpleTutorial("blend_$id", navBuild, helpBlend)

        tutorials["adj_$id"] = azTutorial {
            scene(id = "adj_${id}_intro", content = { Box(Modifier.fillMaxSize()) }) {
                card(
                    title = navAdjust,
                    text = helpAdj,
                    highlight = AzHighlight.Item("adj_$id")
                )
            }
        }

        tutorials["invert_$id"] = createSimpleTutorial("invert_$id", navInvert, helpInvert)
        tutorials["balance_$id"] = createSimpleTutorial("balance_$id", navBalance, helpBalance)
        tutorials["eraser_$id"] = createSimpleTutorial("eraser_$id", navEraser, helpEraser)
        tutorials["blur_$id"] = createSimpleTutorial("blur_$id", navBlur, helpBlur)
        tutorials["liquify_$id"] = createSimpleTutorial("liquify_$id", navLiquify, helpLiquify)
        tutorials["dodge_$id"] = createSimpleTutorial("dodge_$id", navDodge, helpDodge)
        tutorials["burn_$id"] = createSimpleTutorial("burn_$id", navBurn, helpBurn)

        tutorials["iso_$id"] = azTutorial {
            scene(id = "iso_${id}_intro", content = { Box(Modifier.fillMaxSize()) }) {
                card(
                    title = navIsolate,
                    text = helpIso,
                    highlight = AzHighlight.Item("iso_$id")
                )
            }
        }

        tutorials["line_$id"] = createSimpleTutorial("line_$id", navOutline, helpLine)
        tutorials["help_layer_$id"] = createSimpleTutorial("help_layer_$id", navHelp, helpHelpLayer)
    }

    return tutorials
}
