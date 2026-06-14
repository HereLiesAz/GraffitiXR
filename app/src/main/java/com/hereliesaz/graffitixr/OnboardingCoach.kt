package com.hereliesaz.graffitixr

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.onboarding.ModeOnboardingOverlay
import com.hereliesaz.graffitixr.design.R as DesignR
import kotlinx.coroutines.delay

/**
 * One adaptive coaching step: the single most relevant "do this next" hint for the user's *current*
 * state. [targetId] is the rail item the pointer aims at (null = text only). [lines] are revealed
 * one at a time. [key] is stable per situation, so the same step is never re-derived as "new" and is
 * remembered as shown once it has been seen.
 *
 * [lineTargets], when present, gives a per-line target id so a single step's pointer can move between
 * rail items as the user pages through its lines (e.g. walking Open → Sketch → Text after the Design
 * menu opens). An entry of null — or running past the list — falls back to [targetId].
 */
data class CoachStep(
    val key: String,
    val targetId: String?,
    val lines: List<String>,
    val lineTargets: List<String?>? = null,
) {
    /** The rail item the pointer should aim at for [lineIdx]: per-line override, else [targetId]. */
    fun targetForLine(lineIdx: Int): String? = lineTargets?.getOrNull(lineIdx) ?: targetId
}

/**
 * Derives the current coaching step purely from app state — the coach adapts to the user, it never
 * forces a fixed path or waits on one specific action. As the user's mode / layers / wall target
 * change, the relevant step changes with them and naturally moves forward; when there is nothing
 * useful to suggest it returns null (the coach stays quiet). The lines come from the existing,
 * already-localized per-mode onboarding arrays — sliced so each milestone shows only what's relevant
 * to where the user is right now, instead of the whole array at once.
 */
@Composable
fun rememberCoachStep(editor: EditorUiState, ar: ArUiState): CoachStep? {
    val ctx = LocalContext.current
    val hasTarget = ar.isAnchorEstablished
    val hasLayers = editor.layers.isNotEmpty()
    val activeLayer = editor.layers.firstOrNull { it.id == editor.activeLayerId }
    val hasWallPhoto = editor.backgroundBitmap != null
    val firstLayerTarget = editor.layers.firstOrNull()?.let { layerId(it) }
    val layerPointer = (activeLayer ?: editor.layers.firstOrNull())?.let { layerId(it) }

    return remember(
        editor.editorMode, hasLayers, activeLayer?.id, firstLayerTarget, layerPointer,
        hasWallPhoto, hasTarget,
    ) {
        fun arr(id: Int): List<String> = ctx.resources.getStringArray(id).toList()
        fun lines(src: List<String>, vararg idx: Int): List<String> {
            val out = ArrayList<String>(idx.size)
            for (i in idx) src.getOrNull(i)?.let { out.add(it) }
            return out
        }

        val design = arr(DesignR.array.onboarding_design)
        val designLayers = arr(DesignR.array.onboarding_design_layers)
        val arLines = arr(DesignR.array.onboarding_ar)
        val overlay = arr(DesignR.array.onboarding_overlay)
        val mockup = arr(DesignR.array.onboarding_mockup)
        val trace = arr(DesignR.array.onboarding_trace)

        when (editor.editorMode) {
            EditorMode.DESIGN -> when {
                // Intro on 'Design', then — once the user presses it open — walk the pointer across
                // the layer sources (Open / Sketch / Text), explaining each in turn before they pick.
                // Falls back to the plain 2-line step if the per-source copy is missing (e.g. an
                // incomplete localization) so the card never shows a blank line.
                !hasLayers -> {
                    val intro = design.getOrNull(0)
                    if (intro != null && designLayers.isNotEmpty()) {
                        CoachStep(
                            key = "coach.design.add",
                            targetId = "host.design",
                            lines = listOf(intro) + designLayers,
                            lineTargets = listOf("host.design", "design.addImg", "design.addDraw", "design.addText"),
                        )
                    } else {
                        CoachStep("coach.design.add", "host.design", lines(design, 0, 1))
                    }
                }
                activeLayer == null -> CoachStep("coach.design.select", firstLayerTarget, lines(design, 2))
                else -> CoachStep("coach.design.use", "host.modes", lines(design, 3))
            }
            EditorMode.OVERLAY -> when {
                !hasLayers -> CoachStep("coach.overlay.add", "host.design", lines(overlay, 0, 1))
                else -> CoachStep("coach.overlay.place", layerPointer, lines(overlay, 2))
            }
            EditorMode.MOCKUP -> when {
                !hasWallPhoto -> CoachStep("coach.mockup.wall", "mode.mockup.wall", lines(mockup, 0, 1))
                !hasLayers -> CoachStep("coach.mockup.add", "host.design", lines(mockup, 2))
                else -> null
            }
            EditorMode.TRACE -> when {
                !hasLayers -> CoachStep("coach.trace.add", "host.design", lines(trace, 0, 1))
                else -> CoachStep("coach.trace.freeze", "mode.trace.freeze", lines(trace, 2))
            }
            EditorMode.AR -> when {
                // Capture itself is modal-gated, so the coach is hidden during it; this points the
                // user at starting the capture in the first place.
                !hasTarget -> CoachStep("coach.ar.target", "target.create", lines(arLines, 0, 1, 2, 4))
                !hasLayers -> CoachStep("coach.ar.add", "host.design", lines(arLines, 3))
                else -> null
            }
            EditorMode.STENCIL -> null
        }?.takeIf { it.lines.isNotEmpty() }
    }
}

/**
 * Renders the adaptive coach for [step]. It waits for the user to finish what they're doing before
 * surfacing: hidden during an active gesture and for a short settle afterwards, so the next step
 * never interrupts an in-progress action. A step's [CoachStep.lines] are walked one at a time by the
 * underlying overlay (screen tap or idle timer); once past the last line [onSeen] fires so the step
 * is remembered as shown and the coach falls quiet until the user's state changes.
 */
@Composable
fun OnboardingCoachOverlay(
    step: CoachStep,
    gestureInProgress: Boolean,
    lineIdx: Int,
    boundsFor: (String) -> Rect?,
    onAdvance: () -> Unit,
    onSeen: () -> Unit,
    onVisibilityChanged: (Boolean) -> Unit = {},
) {
    var settled by remember(step.key) { mutableStateOf(false) }
    LaunchedEffect(step.key, gestureInProgress) {
        settled = false
        if (!gestureInProgress) {
            delay(700)
            settled = true
        }
    }
    // Report whether the coach is actually surfaced. A rail interaction must only advance a *visible*
    // step — otherwise a startup/selection sync that fires onInteraction before the coach appears
    // would silently skip its first line (the "tap Design" intro the user needs to see).
    val visible = settled && !gestureInProgress
    LaunchedEffect(visible) { onVisibilityChanged(visible) }
    DisposableEffect(Unit) { onDispose { onVisibilityChanged(false) } }

    if (gestureInProgress || !settled) return

    // The current line is controlled by the caller, so two sources can drive it: the overlay's own
    // screen tap (canvas), and a matching rail interaction (rail taps are consumed by the rail and
    // never reach this overlay, so they advance through the hoisted state instead). The pointer
    // target is resolved per line — for a multi-target step the arrow moves item to item.
    val targetBounds = step.targetForLine(lineIdx)?.let(boundsFor)
    ModeOnboardingOverlay(
        positionKey = step.key,
        step = lineIdx,
        lines = step.lines,
        onAdvance = onAdvance,
        onDismiss = onSeen,
        targetBounds = targetBounds,
    )
}
