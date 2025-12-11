package com.hereliesaz.graffitixr.utils

import android.graphics.Bitmap
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.tasks.await
import kotlin.math.PI
import kotlin.math.abs

object BackgroundRemover {

    suspend fun removeBackground(bitmap: Bitmap): Bitmap? {
        val options = SubjectSegmenterOptions.Builder()
            .enableForegroundBitmap()
            .build()
        val segmenter = SubjectSegmentation.getClient(options)

        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = segmenter.process(image).await()
            result.foregroundBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            segmenter.close()
        }
    }
}

/**
 * A customized transform gesture detector that ONLY triggers when 2 or more pointers are down.
 * This prevents single-finger panning from interfering with other gestures or background interactions.
 */
suspend fun PointerInputScope.detectTwoFingerTransformGestures(
    panZoomLock: Boolean = false,
    onGesture: (centroid: Offset, pan: Offset, zoom: Float, rotation: Float) -> Unit
) {
    awaitEachGesture {
        var rotation = 0f
        var zoom = 1f
        var pan = Offset.Zero
        var pastTouchSlop = false
        val touchSlop = viewConfiguration.touchSlop

        awaitFirstDown(requireUnconsumed = false)

        do {
            val event = awaitPointerEvent()
            val canceled = event.changes.any { it.isConsumed }
            if (canceled) return@awaitEachGesture

            // Key Change: Only process if 2 or more pointers are down
            val downCount = event.changes.count { it.pressed }
            if (downCount < 2) {
                // If we dropped to 1 pointer, we stop processing transforms here to avoid
                // single-finger panning taking over unexpectedly.
                // We just wait for the next event loop.
                continue
            }

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
                    pastTouchSlop = true
                }
            }

            if (pastTouchSlop) {
                val centroid = event.calculateCentroid(useCurrent = false)
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
    }
}