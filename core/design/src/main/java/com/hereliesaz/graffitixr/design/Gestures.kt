package com.hereliesaz.graffitixr.design

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import kotlin.math.PI
import kotlin.math.abs

suspend fun PointerInputScope.detectSmartOverlayGestures(
    getValidBounds: () -> Rect,
    onGestureStart: () -> Unit = {},
    onGestureEnd: () -> Unit = {},
    onGesture: (centroid: Offset, pan: Offset, zoom: Float, rotation: Float) -> Unit
) {
    awaitEachGesture {
        var zoom = 1f
        var rotation = 0f
        var pan = Offset.Zero
        var pastTouchSlop = false
        val touchSlop = viewConfiguration.touchSlop

        awaitFirstDown(requireUnconsumed = false)
        onGestureStart()

        do {
            val event = awaitPointerEvent()
            val canceled = event.changes.any { it.isConsumed }
            if (canceled) {
                onGestureEnd()
                break
            }

            val pointerInputChanges = event.changes

            val isChanged = pointerInputChanges.any { it.positionChanged() }
            if (!isChanged) continue

            val centroidSize = event.calculateCentroidSize(useCurrent = false)
            val zoomChange = event.calculateZoom()
            val rotationChange = event.calculateRotation()
            val panChange = event.calculatePan()

            if (!pastTouchSlop) {
                zoom *= zoomChange
                rotation += rotationChange
                pan += panChange

                val centroidSizeChange = abs(centroidSize - event.calculateCentroidSize(useCurrent = true))
                val rotationChangeDegrees = abs(rotation * 180f / PI.toFloat())
                val panAmount = pan.getDistance()

                if (zoom > 1.1f || zoom < 0.9f || rotationChangeDegrees > 10.0 || panAmount > touchSlop) {
                    pastTouchSlop = true
                }
            }

            if (pastTouchSlop) {
                val centroid = event.calculateCentroid(useCurrent = false)
                if (rotationChange != 0f || zoomChange != 1f || panChange != Offset.Zero) {
                    onGesture(centroid, panChange, zoomChange, rotationChange)
                }
                pointerInputChanges.forEach {
                    if (it.positionChanged()) {
                        it.consume()
                    }
                }
            }
        } while (event.changes.any { it.pressed })

        onGestureEnd()
    }
}
