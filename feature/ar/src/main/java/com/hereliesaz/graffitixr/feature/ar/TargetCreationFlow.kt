// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/TargetCreationFlow.kt
package com.hereliesaz.graffitixr.feature.ar

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import java.nio.ByteBuffer
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
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
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
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

private data class SelectionStroke(
    val nx: Float,
    val ny: Float,
    val nr: Float
)

@Composable
fun TargetCreationUi(
    uiState: ArUiState,
    isRightHanded: Boolean,
    captureStep: CaptureStep,
    isWaitingForTap: Boolean,
    isLoading: Boolean,
    strings: AppStrings,
    onConfirm: (bitmap: Bitmap?, mask: Bitmap?, depthBuffer: ByteBuffer?, depthW: Int, depthH: Int, depthStride: Int, intrinsics: FloatArray?, viewMatrix: FloatArray?) -> Unit,
    onRetake: () -> Unit,
    onCancel: () -> Unit,
    onUnwarpConfirm: (List<Offset>) -> Unit,
    onMaskConfirmed: (Bitmap) -> Unit,
    onRequestCapture: () -> Unit,
    onUpdateUnwarpPoints: (List<Offset>) -> Unit,
    onSetActiveUnwarpPoint: (Int) -> Unit,
    onSetMagnifierPosition: (Offset) -> Unit,
    onUpdateMaskPath: (Path?) -> Unit,
    onBeginErase: () -> Unit,
    onEraseAtPoint: (Float, Float) -> Unit,
    onUndoErase: () -> Unit,
    onRedoErase: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (captureStep) {
            CaptureStep.MASK, CaptureStep.REVIEW -> {
                TargetRefinementScreen(
                    rawBitmap = uiState.tempCaptureBitmap,
                    annotatedBitmap = uiState.annotatedCaptureBitmap,
                    canUndo = uiState.canUndoErase,
                    canRedo = uiState.canRedoErase,
                    strings = strings,
                    onNext = { strokes ->
                         val mask = if (strokes.isEmpty()) null 
                                    else rasterizeStrokes(strokes, uiState.tempCaptureBitmap?.width ?: 512, uiState.tempCaptureBitmap?.height ?: 512)
                         
                         val refined = if (mask != null && uiState.tempCaptureBitmap != null) {
                             applyMaskToBitmap(uiState.tempCaptureBitmap, mask)
                         } else uiState.tempCaptureBitmap
                         
                         if (refined != null) onMaskConfirmed(refined)
                    },
                    onRetake = onRetake,
                    onCancel = onCancel,
                    onBeginErase = onBeginErase,
                    onEraseAtPoint = onEraseAtPoint,
                    onUndoErase = onUndoErase,
                    onRedoErase = onRedoErase
                )
            }
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
            else -> {
                if (isWaitingForTap) {
                    Box(Modifier.fillMaxSize()) {
                         TargetInstructionCard(
                            isWaitingForTap = true,
                            captureStep = CaptureStep.NONE,
                            strings = strings,
                            onCancel = onCancel,
                            onNext = null,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)
                        )
                    }
                }
            }
        }
        
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = HotPink)
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
        captureStep == CaptureStep.RECTIFY -> Pair("RECTIFY SURFACE", "Now, drag the corners to align the cyan frame with the flat surface of your wall.")
        captureStep == CaptureStep.MASK || captureStep == CaptureStep.REVIEW -> Pair("REFINE TARGET", "Tap any area to remove it, or drag your finger through markings you want to exclude.")
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
        Box(modifier = Modifier.fillMaxSize().padding(top = 40.dp, bottom = 260.dp).onSizeChanged { boxSize = it }, contentAlignment = Alignment.Center) {
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
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)
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

    Canvas(modifier = Modifier.fillMaxSize()) {
        if (points.size == 4) {
            val path = Path().apply {
                moveTo(imgX + points[0].x * imgW, imgY + points[0].y * imgH)
                lineTo(imgX + points[1].x * imgW, imgY + points[1].y * imgH)
                lineTo(imgX + points[2].x * imgW, imgY + points[2].y * imgH)
                lineTo(imgX + points[3].x * imgW, imgY + points[3].y * imgH)
                close()
            }
            drawPath(path, Color.Cyan, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f))
        }
    }
    
    points.forEachIndexed { index, point ->
        val screenX = imgX + point.x * imgW
        val screenY = imgY + point.y * imgH
        
        Box(
            modifier = Modifier
                .offset(
                    x = with(density) { (screenX).toDp() } - 20.dp,
                    y = with(density) { (screenY).toDp() } - 20.dp
                )
                .size(40.dp)
                .background(Color.White.copy(alpha = 0.5f), CircleShape)
                .border(2.dp, Color.Cyan, CircleShape)
                .pointerInput(index) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val nx = dragAmount.x / imgW
                        val ny = dragAmount.y / imgH
                        val newList = points.toMutableList()
                        newList[index] = Offset(
                            (points[index].x + nx).coerceIn(0f, 1f),
                            (points[index].y + ny).coerceIn(0f, 1f)
                        )
                        onUpdatePoints(newList)
                    }
                }
        )
    }
}

@Composable
private fun TargetRefinementScreen(
    rawBitmap: Bitmap?,
    annotatedBitmap: Bitmap?,
    canUndo: Boolean,
    canRedo: Boolean,
    strings: AppStrings,
    onNext: (List<SelectionStroke>) -> Unit,
    onRetake: () -> Unit,
    onCancel: () -> Unit,
    onBeginErase: () -> Unit,
    onEraseAtPoint: (Float, Float) -> Unit,
    onUndoErase: () -> Unit,
    onRedoErase: () -> Unit
) {
    val strokes = remember { mutableStateListOf<SelectionStroke>() }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    Box(modifier = Modifier.fillMaxSize().onSizeChanged { boxSize = it }) {

        Box(modifier = Modifier.fillMaxSize().padding(top = 40.dp, bottom = 260.dp), contentAlignment = Alignment.Center) {
            rawBitmap?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Raw Capture",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }

            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
            annotatedBitmap?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Feature Review",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }

            if (rawBitmap != null && boxSize != IntSize.Zero) {
                val bmpW = rawBitmap.width.toFloat()
                val bmpH = rawBitmap.height.toFloat()
                val boxW = boxSize.width.toFloat()
                val boxH = boxSize.height.toFloat()
                val scale = if (bmpW / bmpH > boxW / boxH) boxW / bmpW else boxH / bmpH
                val imgW = bmpW * scale
                val imgH = bmpH * scale
                val imgX = (boxW - imgW) / 2f
                val imgY = (boxH - imgH) / 2f

                Canvas(
                    modifier = Modifier.fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures { pos ->
                                val nx = ((pos.x - imgX) / imgW).coerceIn(0f, 1f)
                                val ny = ((pos.y - imgY) / imgH).coerceIn(0f, 1f)
                                onBeginErase()
                                onEraseAtPoint(nx, ny)
                                recordStroke(pos, imgX, imgY, imgW, imgH, 80f / imgW, strokes)
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { pos -> recordStroke(pos, imgX, imgY, imgW, imgH, 80f / imgW, strokes) },
                                onDrag = { change, _ -> recordStroke(change.position, imgX, imgY, imgW, imgH, 80f / imgW, strokes) }
                            )
                        }
                ) {
                    drawStrokes(strokes, imgX, imgY, imgW, imgH)
                }
            }
        }

        Column(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onUndoErase, enabled = canUndo) {
                    Icon(Icons.Default.Refresh, contentDescription = "Undo", tint = if (canUndo) Color.White else Color.Gray, modifier = Modifier.size(32.dp).graphicsLayer { rotationY = 180f })
                }
                IconButton(onClick = onRedoErase, enabled = canRedo) {
                    Icon(Icons.Default.Refresh, contentDescription = "Redo", tint = if (canRedo) Color.White else Color.Gray, modifier = Modifier.size(32.dp))
                }
                
                Icon(
                    imageVector = Icons.Default.Clear,
                    contentDescription = "Reset Strokes",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp).clickable { strokes.clear() }
                )
                
                AzButton(text = "RETAKE", onClick = onRetake, color = HotPink, shape = AzButtonShape.RECTANGLE)
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            TargetInstructionCard(
                isWaitingForTap = false,
                captureStep = CaptureStep.REVIEW,
                strings = strings,
                onCancel = onCancel,
                onNext = { onNext(strokes) }
            )
        }
    }
}

private fun recordStroke(pos: Offset, imgX: Float, imgY: Float, imgW: Float, imgH: Float, brushNorm: Float, strokes: MutableList<SelectionStroke>) {
    val nx = ((pos.x - imgX) / imgW).coerceIn(0f, 1f)
    val ny = ((pos.y - imgY) / imgH).coerceIn(0f, 1f)
    strokes.add(SelectionStroke(nx, ny, brushNorm))
}

private fun DrawScope.drawStrokes(strokes: List<SelectionStroke>, imgX: Float, imgY: Float, imgW: Float, imgH: Float) {
    for (s in strokes) {
        drawCircle(
            color = Color(0xBBCC2200),
            radius = s.nr * imgW,
            center = Offset(imgX + s.nx * imgW, imgY + s.ny * imgH)
        )
    }
}

private fun rasterizeStrokes(strokes: List<SelectionStroke>, width: Int, height: Int): Bitmap {
    val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(mask)
    canvas.drawColor(android.graphics.Color.WHITE)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { 
        style = Paint.Style.FILL 
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    for (s in strokes) {
        canvas.drawCircle(s.nx * width, s.ny * height, s.nr * width, paint)
    }
    return mask
}

private fun applyMaskToBitmap(bitmap: Bitmap, mask: Bitmap): Bitmap {
    val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(out)
    canvas.drawBitmap(bitmap, 0f, 0f, null)
    val paint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    canvas.drawBitmap(mask, 0f, 0f, paint)
    return out
}

@Composable
fun TargetCreationBackground(uiState: ArUiState, captureStep: CaptureStep, onInitUnwarpPoints: (List<Offset>) -> Unit) {}
