package com.hereliesaz.graffitixr.utils

import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * A custom gesture detector to distinguish between pinch-to-zoom, two-finger rotation,
 * and two-finger dragging (panning).
 *
 * This class tracks two pointers and analyzes their movement to provide callbacks for
 * scaling, rotation, and panning events.
 */
class MultiGestureDetector(private val listener: OnMultiGestureListener) {

    interface OnMultiGestureListener {
        fun onScale(scaleFactor: Float)
        fun onRotate(rotationDelta: Float)
        fun onPan(deltaX: Float, deltaY: Float)
    }

    private var ptrID1 = INVALID_POINTER_ID
    private var ptrID2 = INVALID_POINTER_ID

    private var prevX1 = 0f
    private var prevY1 = 0f
    private var prevX2 = 0f
    private var prevY2 = 0f

    private var prevSpan = 0f
    private var prevAngle = 0f

    /**
     * Handles touch events and dispatches gesture callbacks.
     *
     * @param event The motion event to process.
     * @return True if the event was handled, false otherwise.
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ptrID1 = event.getPointerId(event.actionIndex)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                ptrID2 = event.getPointerId(event.actionIndex)
                getPointerCoords(event, ptrID1, ptrID2)?.let {
                    prevX1 = it.first
                    prevY1 = it.second
                    prevX2 = it.third
                    prevY2 = it.fourth
                    prevSpan = calculateSpan(prevX1, prevY1, prevX2, prevY2)
                    prevAngle = calculateAngle(prevX1, prevY1, prevX2, prevY2)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (ptrID1 != INVALID_POINTER_ID && ptrID2 != INVALID_POINTER_ID) {
                    getPointerCoords(event, ptrID1, ptrID2)?.let {
                        val currX1 = it.first
                        val currY1 = it.second
                        val currX2 = it.third
                        val currY2 = it.fourth

                        // Calculate pan
                        val prevMidX = (prevX1 + prevX2) / 2
                        val prevMidY = (prevY1 + prevY2) / 2
                        val currMidX = (currX1 + currX2) / 2
                        val currMidY = (currY1 + currY2) / 2
                        val deltaX = currMidX - prevMidX
                        val deltaY = currMidY - prevMidY
                        if (abs(deltaX) > 0 || abs(deltaY) > 0) {
                            listener.onPan(deltaX, deltaY)
                        }

                        // Calculate scale
                        val currSpan = calculateSpan(currX1, currY1, currX2, currY2)
                        if (prevSpan > 0) {
                            val scaleFactor = currSpan / prevSpan
                            if (abs(1 - scaleFactor) > 0.01) { // Threshold to avoid noise
                                listener.onScale(scaleFactor)
                            }
                        }

                        // Calculate rotation
                        val currAngle = calculateAngle(currX1, currY1, currX2, currY2)
                        var angleDelta = currAngle - prevAngle
                        // Handle wrap-around
                        if (angleDelta > 180) angleDelta -= 360
                        if (angleDelta < -180) angleDelta += 360
                        if (abs(angleDelta) > 0.1) { // Threshold to avoid noise
                             listener.onRotate(Math.toRadians(angleDelta.toDouble()).toFloat())
                        }

                        // Update previous values
                        prevX1 = currX1
                        prevY1 = currY1
                        prevX2 = currX2
                        prevY2 = currY2
                        prevSpan = currSpan
                        prevAngle = currAngle
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                ptrID1 = INVALID_POINTER_ID
            }
            MotionEvent.ACTION_POINTER_UP -> {
                ptrID2 = INVALID_POINTER_ID
            }
        }
        return true
    }

    private fun getPointerCoords(event: MotionEvent, id1: Int, id2: Int): Quad<Float>? {
        val index1 = event.findPointerIndex(id1)
        val index2 = event.findPointerIndex(id2)
        return if (index1 != -1 && index2 != -1) {
            Quad(event.getX(index1), event.getY(index1), event.getX(index2), event.getY(index2))
        } else {
            null
        }
    }

    private fun calculateSpan(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }

    private fun calculateAngle(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        return Math.toDegrees(atan2((y1 - y2).toDouble(), (x1 - x2).toDouble())).toFloat()
    }

    private data class Quad<T>(val first: T, val second: T, val third: T, val fourth: T)

    companion object {
        private const val INVALID_POINTER_ID = -1
    }
}