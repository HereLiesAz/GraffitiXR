package com.hereliesaz.graffitixr

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.hereliesaz.graffitixr.design.theme.AppStrings
import com.hereliesaz.graffitixr.design.R as DesignR

/**
 * Maps a rail-item id to the **existing** tutorial text for that item — the per-mode onboarding
 * string arrays and the `help.*` strings. No new copy is authored here; this only *links* the
 * texts we already have to the rail ids that should surface them (see the flow chart in the plan).
 *
 * Returns a pure lookup. Modes resolve to their multi-line onboarding array; every other item
 * resolves to its single help line. Per-layer tool ids (`layer.<id>.tool.<tool>`) map by suffix.
 */
@Composable
fun rememberRailTutorialLines(strings: AppStrings): (String) -> List<String> {
    val ctx = LocalContext.current
    fun array(resId: Int): List<String> = ctx.resources.getStringArray(resId).toList()

    val modeAr = array(DesignR.array.onboarding_ar)
    val modeOverlay = array(DesignR.array.onboarding_overlay)
    val modeMockup = array(DesignR.array.onboarding_mockup)
    val modeTrace = array(DesignR.array.onboarding_trace)

    @Composable
    fun s(any: Any): String = when (any) {
        is Int -> stringResource(any)
        is String -> any
        else -> any.toString()
    }

    val h = strings.help

    val staticMap: Map<String, List<String>> = mapOf(
        "mode.host" to listOf(s(h.modeHost)),
        "mode.ar" to modeAr,
        "mode.overlay" to modeOverlay,
        "mode.mockup" to modeMockup,
        "mode.trace" to modeTrace,
        "target.host" to listOf(s(h.targetHost)),
        "target.scanModeToggle" to listOf(s(h.scanModeToggle)),
        "target.create" to listOf(s(h.create)),
        "design.host" to listOf(s(h.designHost)),
        "design.addImg" to listOf(s(h.addImg)),
        "design.addDraw" to listOf(s(h.addDraw)),
        "design.addText" to listOf(s(h.addText)),
        "design.wall" to listOf(s(h.wall)),
        "project.host.main" to listOf(s(h.projectHost)),
        "project.new" to listOf(s(h.newProject)),
        "project.save" to listOf(s(h.saveProject)),
        "project.load" to listOf(s(h.loadProject)),
        "project.export" to listOf(s(h.exportImage)),
        "project.settings" to listOf(s(h.appSettings)),
        "tool.light" to listOf(s(h.flashlight)),
        "tool.lockTrace" to listOf(s(h.lockTrace)),
    )

    // Per-layer editing tools, keyed by the suffix after ".tool." in the rail id.
    val toolMap: Map<String, List<String>> = mapOf(
        "editText" to listOf(s(h.editText)),
        "size.brush" to listOf(s(h.size)),
        "size.text" to listOf(s(h.size)),
        "font" to listOf(s(h.font)),
        "color" to listOf(s(h.color)),
        "kern" to listOf(s(h.kern)),
        "bold" to listOf(s(h.bold)),
        "italic" to listOf(s(h.italic)),
        "outline" to listOf(s(h.outline)),
        "shadow" to listOf(s(h.shadow)),
        "stencil" to listOf(s(h.stencilGen)),
        "blend" to listOf(s(h.blend)),
        "adj" to listOf(s(h.adj)),
        "invert" to listOf(s(h.invert)),
        "balance" to listOf(s(h.balance)),
        "eraser" to listOf(s(h.eraser)),
        "blur" to listOf(s(h.blur)),
        "liquify" to listOf(s(h.liquify)),
        "dodge" to listOf(s(h.dodge)),
        "burn" to listOf(s(h.burn)),
        "iso" to listOf(s(h.iso)),
        "line" to listOf(s(h.line)),
    )

    // The layer item itself ("layer.<id>" with no ".tool." suffix). help.layer takes a name;
    // the rail id is a sanitized uuid, so use a generic stand-in.
    val layerLine = listOf(h.layer("this layer"))

    return { id ->
        when {
            staticMap.containsKey(id) -> staticMap.getValue(id)
            ".tool." in id -> toolMap[id.substringAfterLast(".tool.")] ?: emptyList()
            id.startsWith("layer.") -> layerLine
            else -> emptyList()
        }
    }
}
