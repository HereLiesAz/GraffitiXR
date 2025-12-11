package com.hereliesaz.graffitixr.utils

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

/**
 * A smart gesture detector that handles both single-finger panning (restricted to bounds)
 * and multi-finger transformations (unrestricted).
 *
 * @param validBounds The bounds within which single-finger drags are allowed.
 * @param onGesture Callback for gesture events.
 * @param onGestureStart Callback when a valid gesture begins.
 * @param onGestureEnd Callback when the gesture ends.
 */
suspend fun PointerInputScope.detectSmartOverlayGestures(
    validBounds: Rect,
    onGestureStart: () -> Unit,
    onGestureEnd: () -> Unit,
    onGesture: (centroid: Offset, pan: Offset, zoom: Float, rotation: Float) -> Unit
) {
    awaitEachGesture {
        var rotation = 0f
        var zoom = 1f
        var pan = Offset.Zero
        var pastTouchSlop = false
        val touchSlop = viewConfiguration.touchSlop
        var isGesturing = false

        awaitFirstDown(requireUnconsumed = false)

        do {
            val event = awaitPointerEvent()
            val canceled = event.changes.any { it.isConsumed }
            if (canceled) return@awaitEachGesture

            val downCount = event.changes.count { it.pressed }
            val centroid = event.calculateCentroid(useCurrent = false)

            // LOGIC:
            // If 1 finger: Check if centroid (finger) is inside validBounds.
            // If 2+ fingers: Always allow.
            val isValidContext = if (downCount == 1) {
                validBounds.contains(centroid)
            } else {
                true // Grace area allowed for multi-touch
            }

            if (!isValidContext && !pastTouchSlop) {
                continue
            }

            // Calculate changes
            val zoomChange = event.calculateZoom()
            val rotationChange = event.calculateRotation()
            val panChange = event.calculatePan()

            if (!pastTouchSlop) {
                zoom *= zoomChange
                rotation += rotationChange
                pan += panChange

                val centroidSize = event.calculateCentroidSize(useCurrent = false)
                val zoomMotion = abs(1 - zoom) * centroidSize
                val rotationMotion = abs(rotation * PI.toFloat() * centroidSize / 180f)
                val panMotion = pan.getDistance()

                if (zoomMotion > touchSlop ||
                    rotationMotion > touchSlop ||
                    panMotion > touchSlop
                ) {
                    // Only lock in if the start was valid
                    if (isValidContext) {
                        pastTouchSlop = true
                        onGestureStart()
                        isGesturing = true
                    }
                }
            }

            if (pastTouchSlop) {
                if (rotationChange != 0f ||
                    zoomChange != 1f ||
                    panChange != Offset.Zero
                ) {
                    onGesture(centroid, panChange, zoomChange, rotationChange)
                }
                event.changes.forEach {
                    if (it.positionChanged()) {
                        it.consume()
                    }
                }
            }
        } while (!canceled && event.changes.any { it.pressed })

        if (isGesturing) {
            onGestureEnd()
        }
    }
}