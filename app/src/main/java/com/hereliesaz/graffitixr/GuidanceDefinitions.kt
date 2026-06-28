package com.hereliesaz.graffitixr

import android.content.Context
import com.hereliesaz.aznavrail.AzNavHostScope
import com.hereliesaz.aznavrail.tutorial.AzInstructionStep
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.design.theme.AppStrings
import com.hereliesaz.graffitixr.design.R as DesignR

/**
 * Per-mode guidance goal ids. Guidance is OFF by default: the Help rail item activates all of these
 * (turning the tour on) and deactivates them (turning it off). While active, the engine shows only
 * the goal whose mode the user is currently in. Nothing here self-activates.
 */
internal val GUIDANCE_GOAL_IDS = listOf("gx.design", "gx.overlay", "gx.mockup", "gx.trace", "gx.ar")

/**
 * Static rail-item ids the guidance edges point at (excludes runtime tokens and
 * the dynamic `highlightSelector`s, which resolve at render time). Validated against the real rail
 * by [RailIntegrityCheck] so a renamed/removed item is caught in debug instead of silently pointing
 * nowhere — the bug that made the old coach aim at the non-existent `mode.mockup.wall`.
 */
internal val GUIDANCE_HIGHLIGHT_IDS =
    setOf("host.design", "host.modes", "mockup.wall", "mode.trace.freeze", "target.create")

/**
 * Declares the reactive status-driven guidance graph (AzNavRail 10.18) that replaces the old
 * hand-built adaptive coach and the removed scripted-tutorial API. The graph reproduces
 * `rememberCoachStep`'s behaviour one-to-one:
 *   - the same milestone predicates become [azStatus] nodes,
 *   - the same per-mode hints become [azEdge] instructions — text reused verbatim from the existing,
 *     already-localized `onboarding_*` string arrays (no new copy is authored), and
 *   - a per-mode [azGoal] that is activated/deactivated by the Help rail item (it does NOT auto-start
 *     on mode entry); while active it routes from the current screen to that mode's milestone and
 *     completes once the milestone is reached.
 *
 * Multi-line steps use [AzInstructionStep] with `advanceWhen` so the callout pages itself forward as
 * the user's state changes, and `highlightSelector` to point at runtime layer items. The instruction
 * overlay is rendered automatically by `AzHostActivityLayout`; nothing is mounted here.
 *
 * Called inside the `AzHostActivityLayout { }` content lambda, the same scope as ConfigureRailItems.
 */
internal fun AzNavHostScope.ConfigureGuidance(
    editorUiState: EditorUiState,
    arUiState: ArUiState,
    context: Context,
    strings: AppStrings,
) {
    val nav = strings.nav
    val design = context.resources.getStringArray(DesignR.array.onboarding_design)
    val overlay = context.resources.getStringArray(DesignR.array.onboarding_overlay)
    val mockup = context.resources.getStringArray(DesignR.array.onboarding_mockup)
    val trace = context.resources.getStringArray(DesignR.array.onboarding_trace)
    val ar = context.resources.getStringArray(DesignR.array.onboarding_ar)
    fun ln(a: Array<String>, i: Int): String = a.getOrNull(i).orEmpty()

    val design0 = "az.screen.${EditorMode.DESIGN.name}"
    val overlay0 = "az.screen.${EditorMode.OVERLAY.name}"
    val mockup0 = "az.screen.${EditorMode.MOCKUP.name}"
    val trace0 = "az.screen.${EditorMode.TRACE.name}"
    val ar0 = "az.screen.${EditorMode.AR.name}"

    // Stay quiet during an active gesture; re-show after a short settle. Mirrors the old coach, which
    // hid mid-gesture and waited ~700 ms before surfacing the next step.
    azSuppressGuide(settleMs = 700L) { editorUiState.gestureInProgress }

    // --- Milestone statuses: the exact predicates rememberCoachStep derived steps from. ---
    azStatus("gx.hasLayers") { editorUiState.layers.isNotEmpty() }
    azStatus("gx.hasActiveLayer") { editorUiState.layers.any { it.id == editorUiState.activeLayerId } }
    azStatus("gx.hasWallPhoto") { editorUiState.backgroundBitmap != null }
    azStatus("gx.hasTarget") { arUiState.isAnchorEstablished }

    // --- DESIGN: add a layer, then tap it to open its tools. ---
    azEdge(
        from = design0,
        to = "gx.hasActiveLayer",
        text = "",
        steps = listOf(
            AzInstructionStep(text = ln(design, 1), highlightItemId = "host.design", advanceWhen = "gx.hasLayers"),
            AzInstructionStep(
                text = ln(design, 2),
                highlightSelector = { editorUiState.layers.firstOrNull()?.let { layerId(it) } },
                advanceWhen = "gx.hasActiveLayer",
            ),
        ),
    )

    // --- OVERLAY: add a layer. ---
    azEdge(
        from = overlay0,
        to = "gx.hasLayers",
        text = "",
        steps = listOf(
            AzInstructionStep(text = ln(overlay, 0), highlightItemId = "host.design"),
            AzInstructionStep(text = ln(overlay, 1), highlightItemId = "host.design", advanceWhen = "gx.hasLayers"),
        ),
    )

    // --- MOCKUP: pick a wall photo first, then add layers on top. ---
    azEdge(
        from = mockup0,
        to = "gx.hasWallPhoto",
        text = "",
        steps = listOf(
            AzInstructionStep(text = ln(mockup, 0), highlightItemId = "mockup.wall"),
            AzInstructionStep(text = ln(mockup, 1), highlightItemId = "mockup.wall", advanceWhen = "gx.hasWallPhoto"),
        ),
    )
    azEdge(from = "gx.hasWallPhoto", to = "gx.hasLayers", text = ln(mockup, 2), highlightItemId = "host.design")

    // --- TRACE: add a layer. ---
    azEdge(
        from = trace0,
        to = "gx.hasLayers",
        text = "",
        steps = listOf(
            AzInstructionStep(text = ln(trace, 0), highlightItemId = "host.design"),
            AzInstructionStep(text = ln(trace, 1), highlightItemId = "host.design", advanceWhen = "gx.hasLayers"),
        ),
    )

    // --- AR: scan & lock a wall target, then add layers. The "just tap the screen" line (ar[2]) is
    // shown by the in-capture hint in MainActivity, where the guidance overlay is suppressed. ---
    azEdge(
        from = ar0,
        to = "gx.hasTarget",
        text = "",
        steps = listOf(
            AzInstructionStep(text = ln(ar, 0), title = strings.ar.targetCreationTitle, highlightItemId = "target.create"),
            AzInstructionStep(text = ln(ar, 1), highlightItemId = "target.create"),
            AzInstructionStep(text = ln(ar, 4), highlightItemId = "target.create", advanceWhen = "gx.hasTarget"),
        ),
    )
    azEdge(from = "gx.hasTarget", to = "gx.hasLayers", text = ln(ar, 3), highlightItemId = "host.design")

    // --- Per-mode goals: NOT auto-started. The Help rail item activates/deactivates them; while a
    // goal is active the engine routes from the current screen to that mode's milestone. ---
    azGoal(id = "gx.design", target = "gx.hasActiveLayer", label = nav.design)
    azGoal(id = "gx.overlay", target = "gx.hasLayers", label = nav.overlay)
    azGoal(id = "gx.mockup", target = "gx.hasLayers", label = nav.mockup)
    azGoal(id = "gx.trace", target = "gx.hasLayers", label = nav.trace)
    azGoal(id = "gx.ar", target = "gx.hasTarget", label = nav.arMode)
}
