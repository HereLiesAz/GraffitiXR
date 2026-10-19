// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/TargetCreationFlow.kt
package com.hereliesaz.graffitixr.feature.ar

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.hereliesaz.graffitixr.common.model.CaptureStep
import com.hereliesaz.graffitixr.design.R as DesignR
import com.hereliesaz.graffitixr.design.theme.AppStrings
import com.hereliesaz.graffitixr.design.theme.HotPink

/**
 * @param rawBitmap the captured photo and [annotatedBitmap] its highlighted-marks mask, as of this
 *   composition. Taken as the two bitmaps, not the whole ArUiState, so the screen recomposes only
 *   when one of them changes rather than on every unrelated AR state tick.
 * @param hasCleanWall / [onCleanWallPhoto] the bare-wall photo, taken with the round shutter at the
 *   bottom of the screen before the Doodle grid is painted. [hasCleanWall] fills the shutter to show
 *   one is already kept (pressing it again retakes it).
 *   The card's Cancel only puts the card and shutter away: its two steps are instructions, not
 *   modes, so tapping the painted marks works the whole time, with or without the card. Doodle
 *   capture itself is left from the rail item or Back.
 * @param latestCapture the photo and mask as they stand when Confirm is tapped, after every queued
 *   mark erase has been applied (`ArViewModel.awaitLatestTargetCapture`). Erases are asynchronous, so
 *   the composition's own bitmaps can predate the last tap; Confirm uses this instead. The default
 *   just returns the composition's bitmaps (previews, tests).
 */
@Composable
fun TargetCreationUi(
    rawBitmap: Bitmap?,
    annotatedBitmap: Bitmap?,
    captureStep: CaptureStep,
    isWaitingForTap: Boolean,
    hasCleanWall: Boolean = false,
    onCleanWallPhoto: (() -> Unit)? = null,
    strings: AppStrings,
    onConfirmTarget: (bitmap: Bitmap?, mask: Bitmap?) -> Unit,
    onRetake: () -> Unit,
    onEraseAtPoint: (Float, Float) -> Unit,
    latestCapture: suspend () -> Pair<Bitmap?, Bitmap?> = { rawBitmap to annotatedBitmap },
) {
    val scope = rememberCoroutineScope()
    val currentLatestCapture by rememberUpdatedState(latestCapture)
    val currentOnConfirmTarget by rememberUpdatedState(onConfirmTarget)
    var confirming by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize()) {
        // CaptureStep.RECTIFY (a manual 4-corner unwarp step) is never set by any writer of
        // captureStep (MainViewModel only ever sets NONE/CAPTURE/REVIEW) — AR's own target
        // capture went straight from CAPTURE to REVIEW once plane-guided rectification landed.
        // MASK is not set by any writer either; its branch below is kept as an alias of REVIEW (the
        // same mark-removal screen) so a writer using the older step name still lands there.
        // UnwarpScreen/UnwarpOverlay themselves are NOT dead: the standalone SphereSLAM AR
        // target flow reuses them directly with local state — only this unreachable branch and the onUnwarpConfirm/isLoading wiring that
        // existed solely to feed it were removed.
        when (captureStep) {
            CaptureStep.MASK, CaptureStep.REVIEW -> {
                FeatureSelectionReview(
                    annotatedBitmap = annotatedBitmap,
                    rawBitmap = rawBitmap,
                    strings = strings,
                    onConfirm = {
                        if (!confirming) {
                            confirming = true
                            scope.launch {
                                try {
                                    // Applies every queued erase first, then reads the state: the
                                    // annotated bitmap IS the mask — its alpha channel
                                    // (opaque = detect, transparent = skip) drives native ORB masking.
                                    val (raw, mask) = currentLatestCapture()
                                    currentOnConfirmTarget(raw, mask)
                                } finally {
                                    confirming = false
                                }
                            }
                        }
                    },
                    onRetake = onRetake,
                    onEraseAtPoint = onEraseAtPoint
                )
            }

            else -> {
                // Re-shown each time Doodle capture is armed again.
                var cardDismissed by remember(isWaitingForTap) { mutableStateOf(false) }
                if (isWaitingForTap && !cardDismissed) {
                    Box(Modifier.fillMaxSize()) {
                        TargetInstructionCard(
                            isWaitingForTap = true,
                            captureStep = CaptureStep.NONE,
                            strings = strings,
                            onCancel = { cardDismissed = true },
                            onNext = null,
                            // Top of screen so it clears the bottom adjustment + undo/redo controls.
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 32.dp)
                        )
                        if (onCleanWallPhoto != null) {
                            ShutterButton(
                                saved = hasCleanWall,
                                onClick = onCleanWallPhoto,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .navigationBarsPadding()
                                    .padding(bottom = 32.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TargetInstructionCard(
    isWaitingForTap: Boolean,
    captureStep: CaptureStep,
    strings: AppStrings,
    onCancel: () -> Unit,
    onNext: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val data = when {
        isWaitingForTap -> Pair(strings.ar.targetCreationTitle, strings.ar.targetCreationText)
        captureStep == CaptureStep.RECTIFY -> Pair(
            stringResource(DesignR.string.target_rectify_title).uppercase(),
            stringResource(DesignR.string.target_rectify_text)
        )
        captureStep == CaptureStep.MASK || captureStep == CaptureStep.REVIEW -> Pair(
            stringResource(DesignR.string.target_remove_marks_title).uppercase(),
            stringResource(DesignR.string.target_erase_hint)
        )
        else -> Pair("", "")
    }
    val title = data.first
    val text = data.second

    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .background(Color(0xCC000000), RoundedCornerShape(16.dp))
                .border(2.dp, Color.Cyan, RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = title,
                    color = Color.Cyan,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = text,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AzButton(
                text = strings.common.cancel.uppercase(),
                onClick = onCancel,
                color = HotPink,
                shape = AzButtonShape.RECTANGLE
            )
            if (onNext != null) {
                AzButton(
                    text = strings.common.next.uppercase(),
                    onClick = onNext,
                    color = HotPink,
                    shape = AzButtonShape.RECTANGLE
                )
            }
        }
    }
}

/**
 * The bare-wall photo button: a camera app's round shutter, no label. A white ring; once a photo is
 * kept, its centre fills cyan (pressing again retakes it). Spoken label via contentDescription.
 */
@Composable
private fun ShutterButton(saved: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(
        if (saved) DesignR.string.target_shutter_retake_cd else DesignR.string.target_shutter_cd
    )
    Box(
        modifier = modifier
            .size(76.dp)
            .clip(CircleShape)
            .semantics { contentDescription = label }
            // The whole 76 dp circle is the touch target, not just the inner disc.
            .clickable(role = Role.Button, onClick = onClick)
            .border(4.dp, Color.White, CircleShape)
            .padding(8.dp)
            .background(if (saved) Color.Cyan else Color.White, CircleShape)
    )
}

/**
 * REVIEW step: shows the user every mark on the wall, each whole mark highlighted over a
 * dimmed photo of the capture (annotatedCaptureBitmap is the despeckled, highlighted mask
 * from isolateMarkings — whole marks, never dots).
 *
 * Tap a mark to remove it, or drag across marks to remove each one the finger passes over;
 * removeMarkAt() flood-fills the WHOLE touched mark out of annotatedCaptureBitmap. The native
 * engine reads the alpha channel of that bitmap as its detection mask (opaque = detect,
 * transparent = skip), so a removed mark stops contributing to the fingerprint automatically.
 *
 * On confirm, annotatedCaptureBitmap is passed directly as the selection mask.
 */
@Composable
private fun FeatureSelectionReview(
    annotatedBitmap: Bitmap?,
    rawBitmap: Bitmap?,
    strings: AppStrings,
    onConfirm: () -> Unit,
    onRetake: () -> Unit,
    onEraseAtPoint: (Float, Float) -> Unit
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    // The gesture handler below outlives recompositions; read the callback through this so it never
    // calls a stale lambda.
    val currentOnEraseAtPoint by rememberUpdatedState(onEraseAtPoint)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = it }
    ) {
        // -- Raw capture as faint context, dimmed so the highlighted marks dominate --
        rawBitmap?.let { bmp ->
            // remember(bmp): asImageBitmap wraps the bitmap in a new object on every call, and the
            // gesture layer recomposes this on every layout change.
            val image = remember(bmp) { bmp.asImageBitmap() }
            Image(
                bitmap = image,
                contentDescription = stringResource(DesignR.string.target_cd_raw_capture),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
        )

        // -- Highlighted marks (the whole-mark fingerprint mask), always shown --
        annotatedBitmap?.let { bmp ->
            val image = remember(bmp) { bmp.asImageBitmap() }
            Image(
                bitmap = image,
                contentDescription = stringResource(DesignR.string.target_cd_detected_marks),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }

        // -- Tap / drag gesture layer for erasing --
        if (rawBitmap != null && boxSize != IntSize.Zero) {
            val bmpW = rawBitmap.width.toFloat()
            val bmpH = rawBitmap.height.toFloat()
            val boxW = boxSize.width.toFloat()
            val boxH = boxSize.height.toFloat()
            val bmpAspect = bmpW / bmpH
            val boxAspect = boxW / boxH
            val imgW: Float
            val imgH: Float
            val imgX: Float
            val imgY: Float
            if (bmpAspect > boxAspect) {
                imgW = boxW; imgH = boxW / bmpAspect
                imgX = 0f; imgY = (boxH - imgH) / 2f
            } else {
                imgH = boxH; imgW = boxH * bmpAspect
                imgX = (boxW - imgW) / 2f; imgY = 0f
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // One gesture handler for BOTH tap and drag so they never compete for the
                    // gesture (the old two-pointerInput setup let the tap detector win, so a drag
                    // only registered on lift). Erase the whole touched mark on touch-DOWN — a tap
                    // erases instantly — and again on every move while the finger stays down, so a
                    // drag clears each mark it crosses live, not after the finger is lifted.
                    .pointerInput(imgX, imgY, imgW, imgH) {
                        // Skip redundant erases at essentially the same spot (cheap, keeps the
                        // mutex-serialized flood-fill from being spammed while dwelling on one mark).
                        // Kept tight so a drag samples often enough that the per-sample snap radius
                        // (eraseColorBlob) covers the gaps between samples — no marks slip through.
                        val minMovePx = 3.dp.toPx()
                        fun eraseAt(pos: Offset, last: Offset?): Boolean {
                            if (last != null && (pos - last).getDistance() < minMovePx) return false
                            val nx = ((pos.x - imgX) / imgW).coerceIn(0f, 1f)
                            val ny = ((pos.y - imgY) / imgH).coerceIn(0f, 1f)
                            currentOnEraseAtPoint(nx, ny)
                            return true
                        }
                        awaitEachGesture {
                            var lastErase: Offset? = null
                            val down = awaitFirstDown(requireUnconsumed = false)
                            // Follow ONLY the finger that started the gesture. Without tracking the id,
                            // a second finger could alternate into `active`, making lastErase jump
                            // between two far-apart points every frame — that defeats the movement
                            // threshold and spams the mutex-serialized flood-fill.
                            val pointerId = down.id
                            if (eraseAt(down.position, lastErase)) lastErase = down.position
                            down.consume()
                            while (true) {
                                val event = awaitPointerEvent()
                                val active = event.changes.firstOrNull { it.id == pointerId } ?: break
                                if (!active.pressed) break
                                if (eraseAt(active.position, lastErase)) lastErase = active.position
                                active.consume()
                            }
                        }
                    }
            )
        }

        // -- Hint: tap/drag removes a whole mark --
        Text(
            text = stringResource(DesignR.string.target_erase_hint),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp, start = 24.dp, end = 24.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(24.dp))
                .padding(horizontal = 16.dp, vertical = 6.dp)
        )

        // -- Retake / Confirm FABs --
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(32.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            FloatingActionButton(
                onClick = onRetake,
                containerColor = MaterialTheme.colorScheme.errorContainer
            ) {
                Icon(Icons.Default.Refresh, contentDescription = strings.common.retake)
            }
            FloatingActionButton(
                onClick = onConfirm,
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(Icons.Default.Check, contentDescription = stringResource(DesignR.string.target_cd_confirm))
            }
        }
    }
}

/**
 * Shared 4-corner drag-to-align UI, reused by the standalone SphereSLAM AR target capture rather
 * than maintaining a separate corner editor.
 */
@Composable
internal fun UnwarpScreen(
    bitmap: Bitmap,
    points: List<Offset>,
    onUpdatePoints: (List<Offset>) -> Unit,
    onConfirm: (List<Offset>) -> Unit,
    onCancel: () -> Unit,
    strings: AppStrings
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 40.dp, bottom = 260.dp)
                .onSizeChanged { boxSize = it },
            contentAlignment = Alignment.TopStart
        ) {
            val baseImage = remember(bitmap) { bitmap.asImageBitmap() }
            Image(
                bitmap = baseImage,
                contentDescription = stringResource(DesignR.string.target_cd_unwarp_base),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )

            if (boxSize != IntSize.Zero) {
                UnwarpOverlay(
                    points = points,
                    onUpdatePoints = onUpdatePoints,
                    boxSize = boxSize,
                    bitmapSize = IntSize(bitmap.width, bitmap.height)
                )
            }
        }

        TargetInstructionCard(
            isWaitingForTap = false,
            captureStep = CaptureStep.RECTIFY,
            strings = strings,
            onCancel = onCancel,
            onNext = { onConfirm(points) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        )
    }
}

@Composable
private fun UnwarpOverlay(
    points: List<Offset>,
    onUpdatePoints: (List<Offset>) -> Unit,
    boxSize: IntSize,
    bitmapSize: IntSize
) {
    val boxW = boxSize.width.toFloat()
    val boxH = boxSize.height.toFloat()
    val bmpW = bitmapSize.width.toFloat()
    val bmpH = bitmapSize.height.toFloat()

    val scale = if (bmpW / bmpH > boxW / boxH) boxW / bmpW else boxH / bmpH
    val imgW = bmpW * scale
    val imgH = bmpH * scale
    val imgX = (boxW - imgW) / 2f
    val imgY = (boxH - imgH) / 2f

    val density = LocalDensity.current

    val currentPoints by rememberUpdatedState(points)
    val currentOnUpdate by rememberUpdatedState(onUpdatePoints)
    // The image's placement in the box is derived from the box size, so it can change without imgW /
    // imgH doing so (the box moving or its sibling padding changing). The gesture coroutine started
    // below lives across such changes, so it reads the placement through this rather than capturing
    // the values of the composition that launched it.
    val placement by rememberUpdatedState(floatArrayOf(imgX, imgY, imgW, imgH))

    var activePointIndex by remember { mutableIntStateOf(-1) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Points as of the latest drag step. Two drag events can arrive before the caller's
                // new list has been recomposed into `currentPoints`; applying the second delta to
                // that stale list would drop the first. Each step builds on this instead, which is
                // reseeded from the latest state at every drag start.
                var working: List<Offset> = emptyList()
                detectDragGestures(
                    onDragStart = { pos ->
                        val (px, py, pw, ph) = placement
                        working = currentPoints
                        val thresholdPx = with(density) { 40.dp.toPx() }
                        activePointIndex = working.indexOfFirst { p ->
                            val sx = px + p.x * pw
                            val sy = py + p.y * ph
                            val dist = Math.sqrt(
                                ((pos.x - sx) * (pos.x - sx) + (pos.y - sy) * (pos.y - sy)).toDouble()
                            )
                            dist < thresholdPx
                        }
                    },
                    onDrag = { change, dragAmount ->
                        val index = activePointIndex
                        if (index != -1 && index < working.size) {
                            change.consume()
                            val (_, _, pw, ph) = placement
                            val nx = dragAmount.x / pw
                            val ny = dragAmount.y / ph

                            val newList = working.toMutableList()
                            val p = newList[index]
                            newList[index] = Offset(
                                (p.x + nx).coerceIn(0f, 1f),
                                (p.y + ny).coerceIn(0f, 1f)
                            )
                            working = newList
                            currentOnUpdate(newList)
                        }
                    },
                    onDragEnd = { activePointIndex = -1 },
                    onDragCancel = { activePointIndex = -1 }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (currentPoints.size == 4) {
                val path = Path().apply {
                    moveTo(imgX + currentPoints[0].x * imgW, imgY + currentPoints[0].y * imgH)
                    lineTo(imgX + currentPoints[1].x * imgW, imgY + currentPoints[1].y * imgH)
                    lineTo(imgX + currentPoints[2].x * imgW, imgY + currentPoints[2].y * imgH)
                    lineTo(imgX + currentPoints[3].x * imgW, imgY + currentPoints[3].y * imgH)
                    close()
                }
                drawPath(
                    path,
                    Color.Cyan,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f)
                )
            }
        }

        currentPoints.forEachIndexed { index, point ->
            val screenX = imgX + point.x * imgW
            val screenY = imgY + point.y * imgH

            Box(
                modifier = Modifier
                    .offset(
                        x = with(density) { screenX.toDp() } - 20.dp,
                        y = with(density) { screenY.toDp() } - 20.dp
                    )
                    .size(40.dp)
                    .background(
                        if (activePointIndex == index) Color.Cyan.copy(alpha = 0.5f)
                        else Color.White.copy(alpha = 0.5f),
                        CircleShape
                    )
                    .border(2.dp, Color.Cyan, CircleShape)
            )
        }
    }
}
