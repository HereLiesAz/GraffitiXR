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
 * Per-mode guidance goal ids. Each [azGoal] below auto-starts on entry to its mode (`autoStartWhen`
 * bound to that mode's `az.screen.*` status) — AzGuidanceController persists completed/dismissed
 * goals in SharedPreferences, so this is a one-time first-run tour per mode, not a repeat nag. AR
 * already had its own hard-coded first-run overlays (`TargetInstructionCard`,
 * `PostTargetInstructionOverlay`); Overlay/Mockup/Trace/Design had none — this wires the ~155 lines
 * of authored, localized per-mode guidance below (previously declared and never reachable, since
 * the Help rail item opens AzNavRail's built-in help overlay instead of toggling this tour) to
 * actually reach a first-time user in every mode, matching AR's guided first run.
 */
internal val GUIDANCE_GOAL_IDS = listOf("gx.design", "gx.overlay", "gx.mockup", "gx.trace", "gx.ar")

/**
 * Static rail-item ids the guidance edges point at (excludes runtime tokens and
 * the dynamic `highlightSelector`s, which resolve at render time). Validated against the real rail
 * by [RailIntegrityCheck] so a renamed/removed item is caught in debug instead of silently pointing
 * nowhere — the bug that made the old coach aim at the non-existent `mode.mockup.wall`.
 */
internal val GUIDANCE_HIGHLIGHT_IDS =
    setOf("item.open", "mockup.wall", "target.create", "mode.design")

/**
 * Rail-item ids addressed by [azHighlight] and [azItemState] post-hoc decorators in
 * [ConfigureRailItems]. Validated by [RailIntegrityCheck] so a renamed item is caught at debug time
 * rather than silently losing its highlight or badge.
 */
internal val DECORATED_IDS = setOf(
    "item.open",
    "target.create", "mode.ar.light", "mode.ar.lock",
    "coop", "coop.host", "coop.join",
    "mode.ar",
    "mode.overlay.light", "mode.overlay.lock",
    "mode.mockup.lock",
    "mode.trace.freeze", "mode.trace.lock",
    "design.adjust", "design.balance", "design.invert", "design.outline", "design.isolate",
)

/**
 * Declares the reactive status-driven guidance graph (AzNavRail 10.18) that replaces the old
 * hand-built adaptive coach and the removed scripted-tutorial API. The graph reproduces
 * `rememberCoachStep`'s behaviour one-to-one:
 *   - the same milestone predicates become [azStatus] nodes,
 *   - the same per-mode hints become [azEdge] instructions — text reused verbatim from the existing,
 *     already-localized `onboarding_*` string arrays (no new copy is authored), and
 *   - a per-mode [azGoal] that does NOT auto-start on mode entry; while active it routes from the
 *     current screen to that mode's milestone and completes once the milestone is reached.
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
    isCapturingTarget: Boolean,
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
    // AR's own TargetCreationUi (TargetInstructionCard) is a full-screen capture modal with its
    // own instructions; this reactive guidance overlay is rendered automatically by
    // AzHostActivityLayout and isn't covered by MainActivity's anyModalActive gate (that only
    // guards overlays mounted in its own onscreen{} block), so without this the AR goal's rail
    // callout stayed stacked on top of the capture card for the whole scan-to-lock sequence.
    azSuppressGuide(settleMs = 0L) { isCapturingTarget }

    // --- Milestone statuses: the exact predicates rememberCoachStep derived steps from. ---
    azStatus("gx.hasDesign") { editorUiState.design != null }
    azStatus("gx.hasWallPhoto") { editorUiState.backgroundBitmap != null }
    azStatus("gx.hasTarget") { arUiState.isAnchorEstablished }
    // A goal's own target status must NOT already be true the moment it auto-starts, or the
    // routing engine finds itself already at the destination and shows nothing — the concrete bug
    // this caused: a returning user who already has a design (the common case, since Design is
    // usually visited before Mockup) entering Mockup for the first time skipped the wall-photo
    // steps entirely, because gx.hasDesign (the goal's target) was already true. Combining it with
    // Mockup's own prerequisite means the goal can't be "already done" without that prerequisite
    // also being met, so the wall-photo chain always gets walked for a first-time visitor. Overlay/
    // Trace/Design have no analogous secondary prerequisite to combine with — see BACKLOG.md for
    // why those three keep the shared target as a documented, real, un-fixed gap rather than a
    // guessed-at one.
    azStatus("gx.mockupReady") { editorUiState.backgroundBitmap != null && editorUiState.design != null }

    // --- DESIGN: add a layer, then tap it to open its tools. ---
    azEdge(
        from = design0,
        to = "gx.hasDesign",
        text = "",
        steps = listOf(
            AzInstructionStep(text = ln(design, 1), highlightItemId = "item.open", advanceWhen = "gx.hasDesign"),
        ),
    )

    // "Open" (item.open) lives under the "mode.design" rail host, which expandWhen-collapses
    // outside Design mode (see MainActivity.kt's ConfigureRailItems and showDesignInstructionsDialog,
    // which hit this exact bug for AR's hard-coded post-target prompt: "tap Open" alone pointed at an
    // invisible control, fixed there by routing through Design first). Every "add a layer" step below
    // that fires outside Design mode highlights "mode.design" instead of "item.open" for the same
    // reason, and reads from the guidance_open_via_design_* resources (localized copy, not composed
    // English fragments) so translated locales aren't left with an English sentence spliced into an
    // otherwise-translated tour — not yet translated into this app's other 14 locales (a known,
    // documented gap; see BACKLOG.md), same as the pre-existing showDesignInstructionsDialog text.
    fun res(id: Int) = context.resources.getString(id)

    // --- OVERLAY: add a layer. ---
    azEdge(
        from = overlay0,
        to = "gx.hasDesign",
        text = "",
        steps = listOf(
            AzInstructionStep(text = ln(overlay, 0), highlightItemId = "mode.design"),
            AzInstructionStep(
                text = res(DesignR.string.guidance_open_via_design_overlay),
                highlightItemId = "mode.design",
                advanceWhen = "gx.hasDesign",
            ),
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
    azEdge(
        from = "gx.hasWallPhoto",
        to = "gx.mockupReady",
        text = res(DesignR.string.guidance_open_via_design_mockup),
        highlightItemId = "mode.design",
    )

    // --- TRACE: add a layer. ---
    azEdge(
        from = trace0,
        to = "gx.hasDesign",
        text = "",
        steps = listOf(
            AzInstructionStep(text = ln(trace, 0), highlightItemId = "mode.design"),
            AzInstructionStep(
                text = res(DesignR.string.guidance_open_via_design_trace),
                highlightItemId = "mode.design",
                advanceWhen = "gx.hasDesign",
            ),
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
    azEdge(
        from = "gx.hasTarget",
        to = "gx.hasDesign",
        text = res(DesignR.string.guidance_open_via_design_ar),
        highlightItemId = "mode.design",
    )

    // --- Per-mode goals: each auto-starts the first time its mode is entered (autoStartWhen bound
    // to that mode's screen status) and routes from the current screen to that mode's milestone.
    // AzGuidanceController tracks completed/dismissed goals in SharedPreferences, so a goal the
    // user already finished or dismissed does not restart on a later visit. Mockup's target is
    // gx.mockupReady, not the shared gx.hasDesign — see that status's own comment above for why. ---
    azGoal(id = "gx.design", target = "gx.hasDesign", label = nav.design, autoStartWhen = design0)
    azGoal(id = "gx.overlay", target = "gx.hasDesign", label = nav.overlay, autoStartWhen = overlay0)
    azGoal(id = "gx.mockup", target = "gx.mockupReady", label = nav.mockup, autoStartWhen = mockup0)
    azGoal(id = "gx.trace", target = "gx.hasDesign", label = nav.trace, autoStartWhen = trace0)
    azGoal(id = "gx.ar", target = "gx.hasTarget", label = nav.arMode, autoStartWhen = ar0)
}
