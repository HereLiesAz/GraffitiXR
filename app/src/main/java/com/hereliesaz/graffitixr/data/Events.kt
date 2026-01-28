{
type: created file
fileName: app/src/main/java/com/hereliesaz/graffitixr/data/Events.kt
fullContent:
package com.hereliesaz.graffitixr.data

import android.graphics.Bitmap

// UI -> ViewModel Events (Feedback)
sealed class FeedbackEvent {
    object VibrateSingle : FeedbackEvent()
    object VibrateDouble : FeedbackEvent()
    data class Toast(val message: String) : FeedbackEvent()
}

// ViewModel -> Activity Events (Capture/Sensors)
sealed class CaptureEvent {
    object RequestCapture : CaptureEvent()
    object RequestCalibration : CaptureEvent()
    data class RequestFingerprint(val bitmap: Bitmap) : CaptureEvent()
    data class RequestMapSave(val path: String) : CaptureEvent() // FIX: New event for uncoupling
}
