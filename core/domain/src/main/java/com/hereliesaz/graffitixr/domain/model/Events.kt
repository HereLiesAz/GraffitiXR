package com.hereliesaz.graffitixr.domain.model

import android.graphics.Bitmap
import android.net.Uri

sealed class CaptureEvent {
    object RequestCapture : CaptureEvent()
    object RequestCalibration : CaptureEvent()
    data class RequestFingerprint(val bitmap: Bitmap) : CaptureEvent()
    data class CaptureSuccess(val uri: Uri) : CaptureEvent()
    data class CaptureFailure(val exception: Exception) : CaptureEvent()
    // FIX: Uncoupled Save Event
    data class RequestMapSave(val path: String) : CaptureEvent()
}

sealed class FeedbackEvent {
    object VibrateSingle : FeedbackEvent()
    object VibrateDouble : FeedbackEvent()
    data class Toast(val message: String) : FeedbackEvent()
}
