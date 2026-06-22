// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/TargetCreationFlow.kt
package com.hereliesaz.graffitixr.feature.ar

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.common.model.CaptureStep
import com.hereliesaz.graffitixr.design.theme.AppStrings
import com.hereliesaz.graffitixr.design.theme.HotPink

@Composable
fun TargetCreationUi(
    uiState: ArUiState,
    captureStep: CaptureStep,
    isWaitingForTap: Boolean,
    isLoading: Boolean,
    strings: AppStrings,
    onConfirmTarget: (bitmap: Bitmap?, mask: Bitmap?) -> Unit,
    onRetake: () -> Unit,
    onCancel: () -> Unit,
    onUnwarpConfirm: (List<Offset>) -> Unit,
    onUpdateUnwarpPoints: (List<Offset>) -> Unit,
    onEraseAtPoint: (Float, Float) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (captureStep) {
            CaptureStep.RECTIFY -> {
                uiState.tempCaptureBitmap?.let { bitmap ->
                    UnwarpScreen(
                        bitmap = bitmap,
                        points = uiState.unwarpPoints,
                        onUpdatePoints = onUpdateUnwarpPoints,
                        onConfirm = onUnwarpConfirm,
                        onCancel = onCancel,
                        strings = strings
                    )
                }
            }

            CaptureStep.MASK, CaptureStep.REVIEW -> {
                FeatureSelectionReview(
                    annotatedBitmap = uiState.annotatedCaptureBitmap,
                    rawBitmap = uiState.tempCaptureBitmap,
                    strings = strings,
                    onConfirm = {
                        // The annotatedCaptureBitmap IS the mask — its alpha channel
                        // (opaque = detect, transparent = skip) drives native ORB masking.
                        onConfirmTarget(uiState.tempCaptureBitmap, uiState.annotatedCaptureBitmap)
                    },
                    onRetake = onRetake,
                    onEraseAtPoint = onEraseAtPoint
                )
            }

            else -> {
                if (isWaitingForTap) {
                    Box(Modifier.fillMaxSize()) {
                        TargetInstructionCard(
                            isWaitingForTap = true,
                            captureStep = CaptureStep.NONE,
                            strings = strings,
                            onCancel = onCancel,
                            onNext = null,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 32.dp)
                        )
                    }
                }
            }
        }

        if (isLoading) {
            Box(modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = HotPink
                )
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
    modifier: Modifier = Modifier
) {
    val data = when {
        isWaitingForTap -> Pair(strings.ar.targetCreationTitle, strings.ar.targetCreationText)
        captureStep == CaptureStep.RECTIFY -> Pair(
            "RECTIFY SURFACE",
            "Now, drag the corners to align the cyan frame with the flat surface of your wall."
        )
        captureStep == CaptureStep.MASK || captureStep == CaptureStep.REVIEW -> Pair(
            "REMOVE MARKS",
            "Tap a mark to remove it, or drag across marks to remove several."
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
                text = "CANCEL",
                onClick = onCancel,
                color = HotPink,
                shape = AzButtonShape.RECTANGLE
            )
            if (onNext != null) {
                AzButton(
                    text = "NEXT",
                    onClick = onNext,
                    color = HotPink,
                    shape = AzButtonShape.RECTANGLE
                )
            }
        }
    }
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = it }
    ) {
        // -- Raw capture as faint context, dimmed so the highlighted marks dominate --
        rawBitmap?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Raw Capture",
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
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Detected marks",
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
                            onEraseAtPoint(nx, ny)
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
            text = "Tap a mark to remove it, or drag across marks to remove several.",
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
                Icon(Icons.Default.Refresh, contentDescription = "Retake")
            }
            FloatingActionButton(
                onClick = onConfirm,
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(Icons.Default.Check, contentDescription = "Confirm")
            }
        }
    }
}

@Composable
private fun UnwarpScreen(
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
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Unwarp Base",
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

    var activePointIndex by remember { mutableIntStateOf(-1) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(imgW, imgH) {
                detectDragGestures(
                    onDragStart = { pos ->
                        val thresholdPx = with(density) { 40.dp.toPx() }
                        activePointIndex = currentPoints.indexOfFirst { p ->
                            val sx = imgX + p.x * imgW
                            val sy = imgY + p.y * imgH
                            val dist = Math.sqrt(
                                ((pos.x - sx) * (pos.x - sx) + (pos.y - sy) * (pos.y - sy)).toDouble()
                            )
                            dist < thresholdPx
                        }
                    },
                    onDrag = { change, dragAmount ->
                        if (activePointIndex != -1) {
                            change.consume()
                            val nx = dragAmount.x / imgW
                            val ny = dragAmount.y / imgH

                            val newList = currentPoints.toMutableList()
                            val p = newList[activePointIndex]
                            newList[activePointIndex] = Offset(
                                (p.x + nx).coerceIn(0f, 1f),
                                (p.y + ny).coerceIn(0f, 1f)
                            )
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
