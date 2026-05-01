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
    tutorials["mode.host"] = createSimpleTutorial("mode.host", navModes, helpModeHost)
    tutorials["mode.ar"] = createSimpleTutorial("mode.ar", navArMode, helpAr)
    tutorials["mode.overlay"] = createSimpleTutorial("mode.overlay", navOverlay, helpOverlay)
    tutorials["mode.mockup"] = createSimpleTutorial("mode.mockup", navMockup, helpMockup)
    tutorials["mode.trace"] = createSimpleTutorial("mode.trace", navTrace, helpTrace)

    // --- Target Menu ---
    tutorials["target.host"] = createSimpleTutorial("target.host", navGrid, helpTargetHost)
    tutorials["target.scanModeToggle"] = createSimpleTutorial("target.scanModeToggle", "$navCanvas / $navMural", helpScanModeToggle)

    tutorials["target.create"] = azTutorial {
        scene(id = "create_intro", content = { Box(Modifier.fillMaxSize()) }) {
            card(
                title = strings.ar.targetCreationTitle,
                text = strings.ar.targetCreationText,
                highlight = AzHighlight.Item("target.create")
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
    tutorials["design.host"] = createSimpleTutorial("design.host", navDesign, helpDesignHost)

    tutorials["design.addImg"] = azTutorial {
        scene(id = "add_img_intro", content = { Box(Modifier.fillMaxSize()) }) {
            card(
                title = helpAddImg,
                text = helpAddImg,
                highlight = AzHighlight.Item("design.addImg")
            )
            card(
                title = helpAdj,
                text = helpAdj,
                highlight = AzHighlight.FullScreen
            )
        }
    }

    tutorials["design.addDraw"] = createSimpleTutorial("design.addDraw", resolve(strings.nav.draw), resolve(strings.help.addDraw))
    tutorials["design.addText"] = createSimpleTutorial("design.addText", resolve(strings.nav.text), resolve(strings.help.addText))
    tutorials["design.wall"] = createSimpleTutorial("design.wall", navWall, helpWall)

    // --- Project Menu ---
    tutorials["project.host"] = createSimpleTutorial("project.host", navProject, helpProjectHost)
    tutorials["project.new"] = createSimpleTutorial("project.new", navNew, helpNewProject)
    tutorials["project.save"] = createSimpleTutorial("project.save", navSave, helpSaveProject)
    tutorials["project.load"] = createSimpleTutorial("project.load", navLoad, helpLoadProject)
    tutorials["project.export"] = createSimpleTutorial("project.export", navExport, helpExportImage)
    tutorials["project.settings"] = createSimpleTutorial("project.settings", navSettings, helpAppSettings)

    // --- Global Tools ---
    tutorials["tool.light"] = createSimpleTutorial("tool.light", navLight, helpFlashlight)

    tutorials["tool.lockTrace"] = azTutorial {
        scene(id = "lock_trace_intro", content = { Box(Modifier.fillMaxSize()) }) {
            card(
                title = strings.editor.lock + " / " + strings.editor.freeze,
                text = helpLockTrace,
                highlight = AzHighlight.Item("tool.lockTrace")
            )
        }
    }

    tutorials["tool.helpMain"] = createSimpleTutorial("tool.helpMain", navHelp, helpHelpMain)

    // --- Layers ---
    layers.forEach { layer ->
        val layerNameHelp = strings.help.layer(layer.name)

        tutorials[layerId(layer)] = createSimpleTutorial(layerId(layer), layerNameHelp, layerNameHelp)
        tutorials[layerId(layer, "editText")] = createSimpleTutorial(layerId(layer, "editText"), navEdit, helpEditText)
        tutorials[layerId(layer, "size")] = createSimpleTutorial(layerId(layer, "size"), strings.editor.brushSize, helpSize)
        tutorials[layerId(layer, "font")] = createSimpleTutorial(layerId(layer, "font"), navFont, helpFont)
        tutorials[layerId(layer, "color")] = createSimpleTutorial(layerId(layer, "color"), navColor, helpColor)
        tutorials[layerId(layer, "kern")] = createSimpleTutorial(layerId(layer, "kern"), navKern, helpKern)
        tutorials[layerId(layer, "bold")] = createSimpleTutorial(layerId(layer, "bold"), navBold, helpBold)
        tutorials[layerId(layer, "italic")] = createSimpleTutorial(layerId(layer, "italic"), navItalic, helpItalic)
        tutorials[layerId(layer, "outline")] = createSimpleTutorial(layerId(layer, "outline"), navOutline, helpOutline)
        tutorials[layerId(layer, "shadow")] = createSimpleTutorial(layerId(layer, "shadow"), navShadow, helpShadow)

        tutorials[layerId(layer, "stencil")] = azTutorial {
            scene(id = "stencil_${layer.id}_intro", content = { Box(Modifier.fillMaxSize()) }) {
                card(
                    title = navStencil,
                    text = helpStencilGen,
                    highlight = AzHighlight.Item(layerId(layer, "stencil"))
                )
            }
        }

        tutorials[layerId(layer, "blend")] = createSimpleTutorial(layerId(layer, "blend"), navBuild, helpBlend)

        tutorials[layerId(layer, "adj")] = azTutorial {
            scene(id = "adj_${layer.id}_intro", content = { Box(Modifier.fillMaxSize()) }) {
                card(
                    title = navAdjust,
                    text = helpAdj,
                    highlight = AzHighlight.Item(layerId(layer, "adj"))
                )
            }
        }

        tutorials[layerId(layer, "invert")] = createSimpleTutorial(layerId(layer, "invert"), navInvert, helpInvert)
        tutorials[layerId(layer, "balance")] = createSimpleTutorial(layerId(layer, "balance"), navBalance, helpBalance)
        tutorials[layerId(layer, "eraser")] = createSimpleTutorial(layerId(layer, "eraser"), navEraser, helpEraser)
        tutorials[layerId(layer, "blur")] = createSimpleTutorial(layerId(layer, "blur"), navBlur, helpBlur)
        tutorials[layerId(layer, "liquify")] = createSimpleTutorial(layerId(layer, "liquify"), navLiquify, helpLiquify)
        tutorials[layerId(layer, "dodge")] = createSimpleTutorial(layerId(layer, "dodge"), navDodge, helpDodge)
        tutorials[layerId(layer, "burn")] = createSimpleTutorial(layerId(layer, "burn"), navBurn, helpBurn)

        tutorials[layerId(layer, "iso")] = azTutorial {
            scene(id = "iso_${layer.id}_intro", content = { Box(Modifier.fillMaxSize()) }) {
                card(
                    title = navIsolate,
                    text = helpIso,
                    highlight = AzHighlight.Item(layerId(layer, "iso"))
                )
            }
        }

        tutorials[layerId(layer, "line")] = createSimpleTutorial(layerId(layer, "line"), navOutline, helpLine)
        tutorials["${layerId(layer)}.help"] = createSimpleTutorial("${layerId(layer)}.help", navHelp, helpHelpLayer)
    }

    return tutorials
}
