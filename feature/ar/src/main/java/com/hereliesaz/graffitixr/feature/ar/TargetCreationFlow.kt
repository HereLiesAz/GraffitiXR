// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/TargetCreationFlow.kt
package com.hereliesaz.graffitixr.feature.ar

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.common.model.CaptureStep

/** A single brush stroke recorded during user interaction with the selection tool. */
private data class SelectionStroke(
    val nx: Float,   // normalized x in image space [0..1]
    val ny: Float,   // normalized y in image space [0..1]
    val nr: Float,   // normalized brush radius in image space
    val isAdd: Boolean
)

/**
 * Orchestrates the multi-step UI flow for capturing, rectifying, and masking physical targets.
 *
 * [onConfirm] receives (capturedBitmap, selectionMask).  selectionMask is an ARGB_8888 bitmap
 * the same size as capturedBitmap: white = include these features, black = exclude.
 * null mask means "use everything" (same as no interaction).
 */
@Composable
fun TargetCreationUi(
    uiState: ArUiState,
    isRightHanded: Boolean,
    captureStep: CaptureStep,
    isLoading: Boolean,
    onConfirm: (Bitmap?, Bitmap?) -> Unit,
    onRetake: () -> Unit,
    onCancel: () -> Unit,
    onUnwarpConfirm: (List<Offset>) -> Unit,
    onMaskConfirmed: (Bitmap) -> Unit,
    onRequestCapture: () -> Unit,
    onUpdateUnwarpPoints: (List<Offset>) -> Unit,
    onSetActiveUnwarpPoint: (Int) -> Unit,
    onSetMagnifierPosition: (Offset) -> Unit,
    onUpdateMaskPath: (Path?) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (captureStep) {
            CaptureStep.CAPTURE -> {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 48.dp)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Cancel",
                            tint = Color.White,
                            modifier = Modifier.size(48.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .border(4.dp, Color.White, CircleShape)
                            .padding(8.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .clickable { onRequestCapture() }
                    )
                }
            }
            CaptureStep.RECTIFY -> {
                UnwarpUi(
                    isRightHanded = isRightHanded,
                    targetImage = uiState.tempCaptureBitmap,
                    points = uiState.unwarpPoints,
                    activePointIndex = uiState.activeUnwarpPointIndex,
                    magnifierPosition = uiState.magnifierPosition,
                    onPointIndexChanged = onSetActiveUnwarpPoint,
                    onUpdateUnwarpPoints = onUpdateUnwarpPoints,
                    onMagnifierPositionChanged = onSetMagnifierPosition,
                    onConfirm = onUnwarpConfirm,
                    onRetake = onRetake
                )
            }
            CaptureStep.MASK -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    uiState.tempCaptureBitmap?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Mask Target",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
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
                            onClick = { uiState.tempCaptureBitmap?.let { onMaskConfirmed(it) } },
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Icon(Icons.Default.Check, contentDescription = "Confirm Mask")
                        }
                    }
                }
            }
            CaptureStep.REVIEW -> {
                FeatureSelectionReview(
                    annotatedBitmap = uiState.annotatedCaptureBitmap,
                    rawBitmap = uiState.tempCaptureBitmap,
                    isAnnotating = uiState.annotatedCaptureBitmap == null && uiState.tempCaptureBitmap != null,
                    onConfirm = { mask -> onConfirm(uiState.tempCaptureBitmap, mask) },
                    onRetake = onRetake
                )
            }
            else -> {}
        }

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .pointerInput(Unit) {},
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/**
 * REVIEW step: shows the ORB-annotated bitmap with interactive brush selection.
 *
 * The annotation image (from native) shows the wall's colour with green blobs over
 * detected feature regions so the user can see what the SLAM engine will track.
 * The user can then paint EXCLUDE strokes (red) over areas they don't want, and
 * ADD strokes (green) to recover areas they accidentally excluded.
 *
 * On confirm, the strokes are rasterized to a mask bitmap that ORB will use.
 * A null mask means "no user interaction — detect everywhere" (fastest path).
 */
@Composable
private fun FeatureSelectionReview(
    annotatedBitmap: Bitmap?,
    rawBitmap: Bitmap?,
    isAnnotating: Boolean,
    onConfirm: (mask: Bitmap?) -> Unit,
    onRetake: () -> Unit
) {
    val displayBitmap = annotatedBitmap ?: rawBitmap

    // Brush mode: true = add (include), false = erase (exclude)
    var addMode by remember { mutableStateOf(false) }

    // Strokes recorded while user paints
    val strokes = remember { mutableStateListOf<SelectionStroke>() }

    // Displayed image size tracked via onSizeChanged so we can map touch → image coords
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Annotated image ──────────────────────────────────────────────────
        displayBitmap?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Feature Review",
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { boxSize = it },
                contentScale = ContentScale.Fit
            )
        }

        // Loading spinner while annotation is computing
        if (isAnnotating) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp),
                color = MaterialTheme.colorScheme.primary
            )
        }

        // ── Selection overlay + gesture capture ──────────────────────────────
        if (displayBitmap != null && boxSize != IntSize.Zero) {
            val bmpW = displayBitmap.width.toFloat()
            val bmpH = displayBitmap.height.toFloat()

            // Compute ContentScale.Fit image rect within the box
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
                imgX = 0f;  imgY = (boxH - imgH) / 2f
            } else {
                imgH = boxH; imgW = boxH * bmpAspect
                imgX = (boxW - imgW) / 2f; imgY = 0f
            }

            // Brush radius in box-pixel space → normalized to image space
            val brushPx = 40f
            val brushNormalized = brushPx / imgW

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(addMode) {
                        detectDragGestures(
                            onDragStart = { pos -> recordStroke(pos, imgX, imgY, imgW, imgH, brushNormalized, addMode, strokes) },
                            onDrag = { change, _ -> recordStroke(change.position, imgX, imgY, imgW, imgH, brushNormalized, addMode, strokes) }
                        )
                    }
                    .pointerInput(addMode) {
                        detectTapGestures { pos -> recordStroke(pos, imgX, imgY, imgW, imgH, brushNormalized, addMode, strokes) }
                    }
            ) {
                drawStrokes(strokes, imgX, imgY, imgW, imgH)
            }
        }

        // ── Mode toggle ───────────────────────────────────────────────────────
        if (!isAnnotating) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = !addMode,
                    onClick = { addMode = false },
                    label = { Text("Exclude") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xAACC2200),
                        selectedLabelColor = Color.White
                    )
                )
                FilterChip(
                    selected = addMode,
                    onClick = { addMode = true },
                    label = { Text("Include") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xAA008833),
                        selectedLabelColor = Color.White
                    )
                )
                if (strokes.isNotEmpty()) {
                    TextButton(onClick = { strokes.clear() }) {
                        Text("Reset", color = Color.White)
                    }
                }
            }
        }

        // ── Retake / Confirm FABs ─────────────────────────────────────────────
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
                onClick = {
                    val mask = if (strokes.isEmpty()) null
                               else rasterizeStrokes(strokes, displayBitmap?.width ?: 512, displayBitmap?.height ?: 512)
                    onConfirm(mask)
                },
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(Icons.Default.Check, contentDescription = "Confirm")
            }
        }
    }
}

/** Clamp and record a single brush point into [strokes], converting box coords to image-normalized coords. */
private fun recordStroke(
    pos: Offset,
    imgX: Float, imgY: Float, imgW: Float, imgH: Float,
    brushNorm: Float,
    isAdd: Boolean,
    strokes: MutableList<SelectionStroke>
) {
    val nx = ((pos.x - imgX) / imgW).coerceIn(0f, 1f)
    val ny = ((pos.y - imgY) / imgH).coerceIn(0f, 1f)
    strokes.add(SelectionStroke(nx, ny, brushNorm, isAdd))
}

/** Draw all strokes as semi-transparent circles on the Compose Canvas. */
private fun DrawScope.drawStrokes(
    strokes: List<SelectionStroke>,
    imgX: Float, imgY: Float, imgW: Float, imgH: Float
) {
    for (s in strokes) {
        val cx = imgX + s.nx * imgW
        val cy = imgY + s.ny * imgH
        val r  = s.nr * imgW
        drawCircle(
            color = if (s.isAdd) Color(0x5500CC44) else Color(0x55CC2200),
            radius = r,
            center = Offset(cx, cy)
        )
    }
}

/**
 * Rasterize recorded strokes to an ARGB_8888 mask bitmap.
 * White = include (default), black = exclude.
 */
private fun rasterizeStrokes(strokes: List<SelectionStroke>, width: Int, height: Int): Bitmap {
    val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(mask)
    canvas.drawColor(android.graphics.Color.WHITE)  // everything included by default
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    for (s in strokes) {
        paint.color = if (s.isAdd) android.graphics.Color.WHITE else android.graphics.Color.BLACK
        canvas.drawCircle(s.nx * width, s.ny * height, s.nr * width, paint)
    }
    return mask
}

/**
 * Handles underlying background processes or visual indicators during target creation.
 */
@Composable
fun TargetCreationBackground(
    uiState: ArUiState,
    captureStep: CaptureStep,
    onInitUnwarpPoints: (List<Offset>) -> Unit
) {
    // Scaffold for background guide elements if needed
}
