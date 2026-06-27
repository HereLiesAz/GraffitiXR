package com.hereliesaz.graffitixr

import android.content.Context
import com.hereliesaz.aznavrail.AzNavHostScope
import com.hereliesaz.aznavrail.tutorial.AZ_ITEM_ACTIVE
import com.hereliesaz.aznavrail.tutorial.AzInstructionStep
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.design.theme.AppStrings
import com.hereliesaz.graffitixr.design.R as DesignR

/**
 * Per-mode guidance goal ids. Each self-activates on entering its mode (via `autoStartWhen`) and
 * is the set the Help button re-activates to replay the tour.
 */
internal val GUIDANCE_GOAL_IDS = listOf("gx.design", "gx.overlay", "gx.mockup", "gx.trace", "gx.ar")

/**
 * Static rail-item ids the guidance edges point at (excludes the runtime [AZ_ITEM_ACTIVE] token and
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
 *   - a per-mode [azGoal] self-activates on mode entry and, once its milestone is reached, completes
 *     and is remembered by the library (SharedPreferences `az_navrail_completed_goals`) so it never
 *     re-fires — the new equivalent of the old "seen" set.
 *
 * Multi-line steps use [AzInstructionStep] with `advanceWhen` so the callout pages itself forward as
 * the user's state changes, and `highlightSelector` / [AZ_ITEM_ACTIVE] to point at runtime layer
 * items. The instruction overlay is rendered automatically by `AzHostActivityLayout`; nothing is
 * mounted here.
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
    // Mode-scoped terminal nudges, so a "now use it" hint only shows in its own mode.
    azStatus("gx.design.choose") {
        editorUiState.editorMode == EditorMode.DESIGN &&
            editorUiState.layers.any { it.id == editorUiState.activeLayerId }
    }
    azStatus("gx.overlay.place") {
        editorUiState.editorMode == EditorMode.OVERLAY && editorUiState.layers.isNotEmpty()
    }
    azStatus("gx.trace.freeze") {
        editorUiState.editorMode == EditorMode.TRACE && editorUiState.layers.isNotEmpty()
    }

    // --- DESIGN: add a layer, then tap it to open its tools; then an ambient "use it" hint. ---
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
    azEdge(from = "gx.design.choose", text = ln(design, 3), highlightItemId = "host.modes")

    // --- OVERLAY: add a layer, then place it. ---
    azEdge(
        from = overlay0,
        to = "gx.hasLayers",
        text = "",
        steps = listOf(
            AzInstructionStep(text = ln(overlay, 0), highlightItemId = "host.design"),
            AzInstructionStep(text = ln(overlay, 1), highlightItemId = "host.design", advanceWhen = "gx.hasLayers"),
        ),
    )
    azEdge(from = "gx.overlay.place", text = ln(overlay, 2), highlightItemId = AZ_ITEM_ACTIVE)

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

    // --- TRACE: add a layer, then freeze into a lightbox. ---
    azEdge(
        from = trace0,
        to = "gx.hasLayers",
        text = "",
        steps = listOf(
            AzInstructionStep(text = ln(trace, 0), highlightItemId = "host.design"),
            AzInstructionStep(text = ln(trace, 1), highlightItemId = "host.design", advanceWhen = "gx.hasLayers"),
        ),
    )
    azEdge(from = "gx.trace.freeze", text = ln(trace, 2), highlightItemId = "mode.trace.freeze")

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

    // --- Per-mode goals: self-activate on mode entry, complete (persisted) at the milestone. ---
    azGoal(id = "gx.design", target = "gx.hasActiveLayer", label = nav.design, autoStartWhen = design0)
    azGoal(id = "gx.overlay", target = "gx.hasLayers", label = nav.overlay, autoStartWhen = overlay0)
    azGoal(id = "gx.mockup", target = "gx.hasLayers", label = nav.mockup, autoStartWhen = mockup0)
    azGoal(id = "gx.trace", target = "gx.hasLayers", label = nav.trace, autoStartWhen = trace0)
    azGoal(id = "gx.ar", target = "gx.hasTarget", label = nav.arMode, autoStartWhen = ar0)
}
