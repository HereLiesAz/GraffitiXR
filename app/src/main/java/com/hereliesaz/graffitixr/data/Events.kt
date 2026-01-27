package com.hereliesaz.graffitixr.data

import android.net.Uri

sealed class CaptureEvent {
    object RequestCapture : CaptureEvent()
    // ADDED: Missing event type required by MainViewModel
    object RequestCalibration : CaptureEvent()
    data class CaptureSuccess(val uri: Uri) : CaptureEvent()
    data class CaptureFailure(val exception: Exception) : CaptureEvent()
}

sealed class FeedbackEvent {
    object VibrateSingle : FeedbackEvent()
    object VibrateDouble : FeedbackEvent()
    data class Toast(val message: String) : FeedbackEvent()
}
