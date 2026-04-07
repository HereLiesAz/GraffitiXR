// ~~~ FILE: ./feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/SketchToolsDialogs.kt ~~~
package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap as AndroidBitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.*

@Composable
fun ColorPickerDialog(
    currentColor: Color,
    history: List<Color>,
    onSelectColor: (Color) -> Unit,
    onDismiss: () -> Unit
) {
    // Decompose current color into HSV
    val initHsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(
        (currentColor.red * 255).toInt(),
        (currentColor.green * 255).toInt(),
        (currentColor.blue * 255).toInt(),
        initHsv
    )

    var hue by remember { mutableFloatStateOf(initHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initHsv[1]) }
    var brightness by remember { mutableFloatStateOf(initHsv[2]) }

    val selectedColor = remember(hue, saturation, brightness) {
        val argb = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness))
        Color(argb).copy(alpha = currentColor.alpha)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Color preview
                Box(
                    Modifier
                        .size(48.dp)
                        .background(selectedColor, CircleShape)
                        .border(2.dp, Color.White.copy(alpha = 0.6f), CircleShape)
                )

                Spacer(Modifier.height(16.dp))

                // Color wheel
                ColorWheel(
                    hue = hue,
                    saturation = saturation,
                    brightness = brightness,
                    modifier = Modifier.size(240.dp),
                    onHueSaturationChanged = { h, s ->
                        hue = h
                        saturation = s
                    }
                )

                Spacer(Modifier.height(12.dp))

                // Brightness slider
                Text(
                    "Brightness",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                    modifier = Modifier.align(Alignment.Start)
                )
                Slider(
                    value = brightness,
                    onValueChange = { brightness = it },
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    AzButton(text = "Cancel", onClick = onDismiss, shape = AzButtonShape.RECTANGLE)
                    Spacer(Modifier.width(8.dp))
                    AzButton(text = "Apply", onClick = { onSelectColor(selectedColor) }, shape = AzButtonShape.RECTANGLE)
                }
            }
        }
    }
}

@Composable
private fun ColorWheel(
    hue: Float,
    saturation: Float,
    brightness: Float,
    modifier: Modifier = Modifier,
    onHueSaturationChanged: (hue: Float, saturation: Float) -> Unit
) {
    var wheelSize by remember { mutableStateOf(IntSize.Zero) }
    var wheelBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    // Regenerate the wheel bitmap when size or brightness changes
    LaunchedEffect(wheelSize, brightness) {
        val px = wheelSize.width
        if (px <= 0) return@LaunchedEffect
        wheelBitmap = generateColorWheelBitmap(px, brightness)
    }

    val selectorPos = remember(hue, saturation, wheelSize) {
        val radius = wheelSize.width / 2f
        val angle = Math.toRadians(hue.toDouble())
        Offset(
            x = (radius + cos(angle) * saturation * radius).toFloat(),
            y = (radius + sin(angle) * saturation * radius).toFloat()
        )
    }

    fun pickFromOffset(offset: Offset) {
        val radius = wheelSize.width / 2f
        if (radius <= 0f) return
        val dx = offset.x - radius
        val dy = offset.y - radius
        val dist = sqrt(dx * dx + dy * dy)
        val newSat = (dist / radius).coerceIn(0f, 1f)
        val newHue = ((Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 360) % 360).toFloat()
        onHueSaturationChanged(newHue, newSat)
    }

    Canvas(
        modifier = modifier
            .onSizeChanged { wheelSize = it }
            .pointerInput(wheelSize) {
                detectTapGestures { offset -> pickFromOffset(offset) }
            }
            .pointerInput(wheelSize) {
                detectDragGestures { _, dragAmount ->
                    val radius = wheelSize.width / 2f
                    if (radius <= 0f) return@detectDragGestures
                    val angle = Math.toRadians(hue.toDouble())
                    val curX = radius + cos(angle) * saturation * radius
                    val curY = radius + sin(angle) * saturation * radius
                    pickFromOffset(Offset((curX + dragAmount.x).toFloat(), (curY + dragAmount.y).toFloat()))
                }
            }
    ) {
        // Draw wheel bitmap
        wheelBitmap?.let { bmp ->
            drawImage(bmp)
        }

        // Draw selector ring
        drawCircle(
            color = Color.Black,
            radius = 12f,
            center = selectorPos,
            style = Stroke(width = 3f)
        )
        drawCircle(
            color = Color.White,
            radius = 10f,
            center = selectorPos,
            style = Stroke(width = 2f)
        )
    }
}

private suspend fun generateColorWheelBitmap(size: Int, brightness: Float): ImageBitmap =
    withContext(Dispatchers.Default) {
        val pixels = IntArray(size * size)
        val center = size / 2f
        val radius = center
        val hsv = FloatArray(3).also { it[2] = brightness }

        for (y in 0 until size) {
            for (x in 0 until size) {
                val dx = (x - center).toDouble()
                val dy = (y - center).toDouble()
                val dist = sqrt(dx * dx + dy * dy)
                pixels[y * size + x] = if (dist <= radius) {
                    val angle = (Math.toDegrees(atan2(dy, dx)) + 360) % 360
                    hsv[0] = angle.toFloat()
                    hsv[1] = (dist / radius).toFloat().coerceIn(0f, 1f)
                    android.graphics.Color.HSVToColor(hsv)
                } else {
                    0 // transparent outside circle
                }
            }
        }

        val bmp = AndroidBitmap.createBitmap(size, size, AndroidBitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, size, 0, 0, size, size)
        bmp.asImageBitmap()
    }

@Composable
fun SizePickerDialog(
    currentSize: Float,
    onSizeChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Brush Size", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(32.dp))

                Box(contentAlignment = Alignment.Center, modifier = Modifier.height(100.dp)) {
                    Box(Modifier.size((currentSize).dp).background(Color.White, CircleShape))
                }

                Slider(value = currentSize, onValueChange = onSizeChange, valueRange = 5f..150f)

                AzButton(text = "Done", onClick = onDismiss, modifier = Modifier.padding(top = 16.dp), shape = AzButtonShape.RECTANGLE)
            }
        }
    }
}
