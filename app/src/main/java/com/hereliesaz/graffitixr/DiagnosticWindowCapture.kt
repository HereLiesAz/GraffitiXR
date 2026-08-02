package com.hereliesaz.graffitixr

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Photographs the app window to a PNG.
 *
 * ## Why `PixelCopy` and not the view hierarchy
 *
 * The obvious implementation — `decorView.draw(Canvas(bitmap))`, or the old drawing cache — produces
 * an image of the Compose UI over a **black rectangle**. The camera preview and the AR overlay are a
 * hardware-composited surface: they are never drawn by the view hierarchy's canvas, they are handed
 * to the compositor. So the one thing a diagnostic screenshot exists to show is the one thing that
 * method cannot capture. `PixelCopy` reads the composited window instead, which is what is actually
 * on the glass.
 *
 * ## What the timestamp on the image means
 *
 * Less than it looks like. The request is raised from the diagnostics tick, dispatched to a
 * coroutine, and satisfied by the compositor some frames later. The image is therefore *shortly
 * after* the transition that asked for it, not at it — which is the useful direction for a
 * correction spike (it shows where the overlay landed) and the awkward one for a lost lock (the
 * camera may already have moved on). It is worth knowing which is which when reading one.
 */
suspend fun captureWindowToPng(
    activity: Activity,
    dest: File,
    maxDim: Int = DIAGNOSTIC_CAPTURE_MAX_DIM,
): File {
    val decor = activity.window?.decorView
        ?: throw IllegalStateException("no window")
    val w = decor.width
    val h = decor.height
    if (w <= 0 || h <= 0) throw IllegalStateException("window not laid out (${w}x$h)")

    val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val status = suspendCancellableCoroutine { cont ->
        try {
            PixelCopy.request(
                activity.window!!,
                full,
                { result -> if (cont.isActive) cont.resume(result) },
                Handler(Looper.getMainLooper()),
            )
        } catch (e: IllegalArgumentException) {
            // Thrown when the window has no backing surface yet — mid-rotation, or a request that
            // raced the Activity going down. A real outcome, not a crash.
            if (cont.isActive) cont.resume(PixelCopy.ERROR_SOURCE_NO_DATA)
        }
    }
    if (status != PixelCopy.SUCCESS) {
        full.recycle()
        throw IllegalStateException("PixelCopy failed (status $status)")
    }

    return withContext(Dispatchers.IO) {
        // Downscaled before encoding, not after: sixteen full-resolution PNGs of a 1080×2400 screen
        // is tens of megabytes written to a user's phone for a debugging aid.
        //
        // 1280 on the long edge is chosen for the SCENE, not the HUD. On a 1080×2400 phone that is a
        // 0.53× reduction, which takes the overlay's 11 sp rows to about six pixels — not reliably
        // legible, and it does not need to be: every number on that HUD is in the report, at full
        // precision, aggregated over a window rather than caught at one instant. What only the
        // image can supply is what the camera was pointed at, and a wall stays identifiable well
        // below this.
        val scale = maxDim.toFloat() / maxOf(w, h).toFloat()
        val out = if (scale < 1f) {
            Bitmap.createScaledBitmap(full, (w * scale).toInt(), (h * scale).toInt(), true)
        } else {
            full
        }
        dest.parentFile?.mkdirs()
        dest.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (out !== full) out.recycle()
        full.recycle()
        dest
    }
}

/** Long-edge cap for a diagnostic capture, in pixels. */
const val DIAGNOSTIC_CAPTURE_MAX_DIM = 1280
