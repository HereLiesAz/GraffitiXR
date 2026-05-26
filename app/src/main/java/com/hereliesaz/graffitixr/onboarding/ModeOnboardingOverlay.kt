package com.hereliesaz.graffitixr.onboarding

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay

@Composable
fun ModeOnboardingOverlay(
    onboarding: ModeOnboarding,
    onDismiss: () -> Unit,
) {
    // First-run per-mode onboarding manages its own step locally (no rail-tap driving).
    var step by rememberSaveable(onboarding.mode) { mutableStateOf(0) }
    ModeOnboardingOverlay(
        positionKey = onboarding.mode,
        step = step,
        lines = onboarding.lines,
        onAdvance = { step++ },
        onDismiss = onDismiss,
    )
}

/**
 * Generic onboarding overlay over arbitrary [lines]. The current [step] is controlled by the
 * caller so any tap source — the non-consuming screen observer below, the per-step timer, or an
 * external rail tap — can drive advancement through the same [onAdvance]. [positionKey] seeds the
 * popup zone and resets the tap observer when the context changes.
 *
 * The overlay never consumes pointer events, so taps fall through to the canvas/editor underneath
 * and the walkthrough never blocks interaction.
 */
@Composable
fun ModeOnboardingOverlay(
    positionKey: Any,
    step: Int,
    lines: List<String>,
    onAdvance: () -> Unit,
    onDismiss: () -> Unit,
    targetBounds: Rect? = null,
) {
    val line = lines.getOrNull(step)
    LaunchedEffect(line) {
        if (line == null) {
            onDismiss()
        }
    }

    if (line == null) {
        return
    }

    // Keep advance current without restarting the pointer loop on every step.
    val advance by rememberUpdatedState(onAdvance)

    // Idle safety-net timer: longer lines linger longer. With the do-it-to-advance walkthrough the
    // user is expected to perform the targeted interaction, so this only fires when they don't —
    // hence the doubled durations. Restarts whenever the step (from any source) or context changes.
    LaunchedEffect(positionKey, step) {
        delay(stepDurationMs(line))
        advance()
    }

    // Pointer geometry: the overlay's own window origin (to convert the target's window-space
    // bounds into local draw coords) and the instruction card's bounds (the pointer's tail).
    var rootWindowOffset by remember { mutableStateOf(Offset.Zero) }
    var cardBoundsLocal by remember { mutableStateOf<Rect?>(null) }
    val pulse by rememberInfiniteTransition(label = "tutorialPointer").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootWindowOffset = it.positionInWindow() }
            .pointerInput(positionKey) {
                awaitPointerEventScope {
                    while (true) {
                        // Observe in the Initial pass and never consume, so the tap still
                        // reaches whatever is underneath while also advancing the step.
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.fastAny { it.changedToDownIgnoreConsumed() }) {
                            advance()
                        }
                    }
                }
            }
    ) {
        // Pick one of nine in-bounds zones based on a (key, step) seed so the
        // text moves between popups but never the same zone twice in a row.
        val seed = positionKey.hashCode()
        val zone = remember(positionKey, step) {
            zoneForSeed(seed * 1000 + step, prevStep = step - 1, prevMode = seed)
        }

        // Pointer aiming at the rail item this step wants the user to act on. Drawn under the
        // text; skipped until both the card and the target have been measured.
        val target = targetBounds
        val card = cardBoundsLocal
        if (target != null && card != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val tl = Offset(target.left - rootWindowOffset.x, target.top - rootWindowOffset.y)
                val targetLocal = Rect(tl, Size(target.width, target.height))
                drawTutorialPointer(card, targetLocal, Color.Cyan, pulse)
            }
        }

        Box(
            modifier = Modifier
                .align(zone)
                // Reserve the bottom strip so a bottom-zone line never lands behind the editor's
                // undo / redo / magic-wand FABs. Top stays at 48dp; bottom clears the FAB row.
                .padding(start = 32.dp, end = 32.dp, top = 48.dp, bottom = 140.dp)
                .widthIn(max = maxWidth * 0.75f)
                .onGloballyPositioned { cardBoundsLocal = it.boundsInParent() }
        ) {
            // Drop-shadow halo so plain text reads on any background.
            Text(
                text = line,
                color = Color.Black.copy(alpha = 0.85f),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Start,
                modifier = Modifier.padding(start = 1.dp, top = 1.dp)
            )
            Text(
                text = line,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Start,
            )
        }
    }
}

private fun stepDurationMs(line: String): Long =
    (6000L + 100L * line.length).coerceIn(6000L, 20000L)

/**
 * Draws a pointer from the instruction [cardLocal] to the [targetLocal] rail item: a pulsing
 * rounded-rect highlight around the target plus an arrow whose tail sits on the card's nearest edge
 * and whose head lands on the target. A dark offset pass underneath keeps it legible on any
 * background. All coordinates are in the overlay's local space.
 */
private fun DrawScope.drawTutorialPointer(
    cardLocal: Rect,
    targetLocal: Rect,
    color: Color,
    pulse: Float,
) {
    val inflate = 6.dp.toPx() + pulse * 4.dp.toPx()
    val hi = Rect(
        targetLocal.left - inflate,
        targetLocal.top - inflate,
        targetLocal.right + inflate,
        targetLocal.bottom + inflate,
    )
    val cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx())
    val baseStroke = 3.dp.toPx()
    val head = 14.dp.toPx()
    val spread = 0.5f

    // Tail: target centre projected onto the card's nearest edge. Head: nearest point on the
    // highlight box to that tail (so the arrow stops at the target, not inside it).
    val tCenter = targetLocal.center
    val tail = Offset(
        tCenter.x.coerceIn(cardLocal.left, cardLocal.right),
        tCenter.y.coerceIn(cardLocal.top, cardLocal.bottom),
    )
    val headPt = Offset(
        tail.x.coerceIn(hi.left, hi.right),
        tail.y.coerceIn(hi.top, hi.bottom),
    )
    // Skip the arrow when the card overlaps the target (nothing sensible to point along).
    val hasArrow = hypot(headPt.x - tail.x, headPt.y - tail.y) > 8.dp.toPx()
    val back = atan2(headPt.y - tail.y, headPt.x - tail.x) + kotlin.math.PI.toFloat()

    fun pass(c: Color, sw: Float, off: Offset) {
        drawRoundRect(
            color = c,
            topLeft = Offset(hi.left + off.x, hi.top + off.y),
            size = Size(hi.width, hi.height),
            cornerRadius = cornerRadius,
            style = Stroke(width = sw),
        )
        if (hasArrow) {
            drawLine(c, tail + off, headPt + off, sw, cap = StrokeCap.Round)
            drawLine(c, headPt + off, Offset(headPt.x + off.x + head * cos(back - spread), headPt.y + off.y + head * sin(back - spread)), sw, cap = StrokeCap.Round)
            drawLine(c, headPt + off, Offset(headPt.x + off.x + head * cos(back + spread), headPt.y + off.y + head * sin(back + spread)), sw, cap = StrokeCap.Round)
        }
    }

    pass(Color.Black.copy(alpha = 0.55f), baseStroke + 2.dp.toPx(), Offset(1.5.dp.toPx(), 1.5.dp.toPx()))
    pass(color.copy(alpha = 0.6f + 0.4f * pulse), baseStroke, Offset.Zero)
}

private val zones: List<Alignment> = listOf(
    Alignment.TopStart, Alignment.TopCenter, Alignment.TopEnd,
    Alignment.CenterStart, Alignment.Center, Alignment.CenterEnd,
    Alignment.BottomStart, Alignment.BottomCenter, Alignment.BottomEnd,
)

private fun zoneForSeed(seed: Int, prevStep: Int, prevMode: Int): Alignment {
    val rng = Random(seed)
    val pick = zones[rng.nextInt(zones.size)]
    if (prevStep < 0) return pick
    val prev = zones[Random(prevMode * 1000 + prevStep).nextInt(zones.size)]
    // Avoid landing in the same zone two popups in a row — rotate to the next
    // distinct zone so successive lines never overlap each other.
    return if (pick == prev) zones[(zones.indexOf(pick) + 1) % zones.size] else pick
}
