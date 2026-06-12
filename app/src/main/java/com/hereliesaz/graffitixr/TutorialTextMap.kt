package com.hereliesaz.graffitixr

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.Layer
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
    val modeDesign = array(DesignR.array.onboarding_design)

    @Composable
    fun s(any: Any): String = when (any) {
        is Int -> stringResource(any)
        is String -> any
        else -> any.toString()
    }

    val h = strings.help

    // Keys MUST match the rail-item ids registered in ConfigureRailItems (and enumerated by
    // RailIdEnumerator / surfaced by buildHelpItems) — otherwise the do-it-to-advance walkthrough
    // produces no steps. These are the real ids, not the older mode.host/design.host/project.* scheme.
    val staticMap: Map<String, List<String>> = mapOf(
        "host.modes" to listOf(s(h.modeHost)),
        "mode.ar" to modeAr,
        "mode.overlay" to modeOverlay,
        "mode.mockup" to modeMockup,
        "mode.trace" to modeTrace,
        "mode.mockup.wall" to listOf(s(h.wall)),
        "mode.trace.freeze" to listOf(s(h.lockTrace)),
        "target.create" to listOf(s(h.create)),
        "host.design" to modeDesign,
        "design.addImg" to listOf(s(h.addImg)),
        "design.addDraw" to listOf(s(h.addDraw)),
        "design.addText" to listOf(s(h.addText)),
        "host.project" to listOf(s(h.projectHost)),
        "proj.new" to listOf(s(h.newProject)),
        "proj.save" to listOf(s(h.saveProject)),
        "proj.load" to listOf(s(h.loadProject)),
        "proj.settings" to listOf(s(h.appSettings)),
        "item.help" to listOf(s(strings.nav.help)),
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

/**
 * Builds the ordered do-it-to-advance walkthrough for the given [mode] and [layers].
 *
 * The sequence is derived from the same two sources of truth the rest of the app already uses, so
 * it can never drift from what is actually on the rail:
 *   - [enumerateRailItemIdRegistrations] gives the rail-item ids in registration order, already
 *     mode-adaptive (AR-only `target.*`, MOCKUP-only `design.wall`) and per-layer.
 *   - [buildHelpItems] is the canonical set of ids that carry help text; intersecting against it
 *     drops items that aren't real walkthrough targets (e.g. `tool.helpMain`, `coop.*`, the
 *     per-layer `.help` placeholder).
 *   - [rememberRailTutorialLines] supplies each step's instruction lines; ids with no text are
 *     skipped (e.g. `wearable.main`).
 *
 * Result is remembered on (layers, mode) so the returned list is structurally stable across
 * recompositions — important because it keys the LaunchedEffect that feeds it to the view model.
 */
@Composable
fun rememberTutorialSequence(
    strings: AppStrings,
    layers: List<Layer>,
    mode: EditorMode,
): List<TutorialStep> {
    val lineFor = rememberRailTutorialLines(strings)
    return remember(layers, mode) {
        val helpKeys = buildHelpItems(strings, layers).keys
        val seen = LinkedHashSet<String>()
        enumerateRailItemIdRegistrations(layers, mode)
            .filter { it in helpKeys && seen.add(it) }
            .mapNotNull { id ->
                val lines = lineFor(id)
                if (lines.isEmpty()) null else TutorialStep(id, lines)
            }
    }
}
