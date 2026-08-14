package com.hereliesaz.graffitixr.design

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
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
        // Whether onGestureStart() has actually fired this cycle -- see below for why this is not
        // simply "== pastTouchSlop", and why onGestureEnd() at the bottom is gated on it too.
        var started = false
        val touchSlop = viewConfiguration.touchSlop

        awaitFirstDown(requireUnconsumed = false)

        do {
            val event = awaitPointerEvent()
            val canceled = event.changes.any { it.isConsumed }
            // Just break, don't call onGestureEnd() here too -- falling through to the single call
            // after the loop (which used to run unconditionally on top of this one) fired
            // onGestureEnd() TWICE for every cancelled gesture: once here, once past the loop.
            if (canceled) break

            val pointerInputChanges = event.changes

            val isChanged = pointerInputChanges.any { it.positionChanged() }
            if (!isChanged) continue

            val zoomChange = event.calculateZoom()
            val rotationChange = event.calculateRotation()
            val panChange = event.calculatePan()

            if (!pastTouchSlop) {
                zoom *= zoomChange
                rotation += rotationChange
                pan += panChange

                // calculateRotation() already returns degrees; the old `rotation * 180/PI`
                // treated the accumulated degrees as radians and tripped the slop ~57x too early.
                val rotationDegrees = abs(rotation)
                val panAmount = pan.getDistance()

                if (zoom > 1.1f || zoom < 0.9f || rotationDegrees > 10.0 || panAmount > touchSlop) {
                    pastTouchSlop = true
                    // Fire exactly on this false->true edge: this is the first point at which the
                    // gesture is actually a drag/pinch/rotate rather than a tap. onGestureStart used
                    // to fire unconditionally on the initial pointer-down above, before slop had any
                    // chance to be crossed -- so a plain single tap (pushHistory() + a full
                    // save-and-broadcast in this app's caller) fired it every time, for a gesture
                    // that never moved anything.
                    started = true
                    onGestureStart()
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

        // Symmetric with the guard above onGestureStart(): only a cycle that actually started one
        // (crossed touch slop) ends one, whether it ended via the break above or falling out of the
        // loop naturally.
        if (started) onGestureEnd()
    }
}
