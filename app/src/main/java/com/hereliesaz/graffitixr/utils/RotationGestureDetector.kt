package com.hereliesaz.graffitixr.utils

import android.view.MotionEvent
import kotlin.math.atan2

class RotationGestureDetector(private val listener: OnRotationGestureListener) {

    private var ptrID1: Int = INVALID_POINTER_ID
    private var ptrID2: Int = INVALID_POINTER_ID
    private var angle: Float = 0f

    interface OnRotationGestureListener {
        fun onRotation(rotationDelta: Float)
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> ptrID1 = event.getPointerId(event.actionIndex)
            MotionEvent.ACTION_POINTER_DOWN -> {
                ptrID2 = event.getPointerId(event.actionIndex)
                val (x1, y1, x2, y2) = getPointerCoordinates(event)
                angle = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble()).toFloat()
            }
            MotionEvent.ACTION_MOVE -> {
                if (ptrID1 != INVALID_POINTER_ID && ptrID2 != INVALID_POINTER_ID) {
                    val (x1, y1, x2, y2) = getPointerCoordinates(event)
                    val newAngle = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble()).toFloat()
                    val delta = newAngle - angle
                    listener.onRotation(delta)
                    angle = newAngle
                }
            }
            MotionEvent.ACTION_UP -> ptrID1 = INVALID_POINTER_ID
            MotionEvent.ACTION_POINTER_UP -> ptrID2 = INVALID_POINTER_ID
            MotionEvent.ACTION_CANCEL -> {
                ptrID1 = INVALID_POINTER_ID
                ptrID2 = INVALID_POINTER_ID
            }
        }
        return true
    }

    private fun getPointerCoordinates(event: MotionEvent): FloatArray {
        val index1 = event.findPointerIndex(ptrID1)
        val index2 = event.findPointerIndex(ptrID2)
        return floatArrayOf(event.getX(index1), event.getY(index1), event.getX(index2), event.getY(index2))
    }

    companion object {
        private const val INVALID_POINTER_ID = -1
    }
}