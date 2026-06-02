package com.hereliesaz.graffitixr.feature.ar.eval

import android.content.Context
import android.net.Uri
import com.google.ar.core.PlaybackStatus
import com.google.ar.core.RecordingConfig
import com.google.ar.core.RecordingStatus
import com.google.ar.core.Session
import timber.log.Timber
import java.io.File

/**
 * Wraps ARCore's Recording & Playback API so the bench can replay one captured wall session
 * deterministically against each mechanism config. Recording must be configured BEFORE the session
 * is resumed; playback dataset must be set while the session is paused.
 */
class ArRecordingController(private val context: Context) {

    fun recordingsDir(): File = File(context.filesDir, "eval/recordings").apply { mkdirs() }

    /**
     * Start recording the live session to an MP4 dataset. Returns the target file, or null if
     * recording could not be started. ARCore throws AR_ERROR_ILLEGAL_STATE if recording is started
     * after the session has resumed (it must be configured at resume time) or if a recording is
     * already active — this is a dev-only bench control, so a failure must never crash the app.
     */
    fun startRecording(session: Session, name: String): File? {
        if (session.recordingStatus == RecordingStatus.OK) {
            Timber.w("eval: recording already in progress; ignoring start")
            return null
        }
        return try {
            val file = File(recordingsDir(), "$name.mp4")
            val config = RecordingConfig(session).setMp4DatasetUri(Uri.fromFile(file))
            session.startRecording(config)
            Timber.i("eval: recording -> ${file.absolutePath}")
            file
        } catch (e: Exception) {
            // Most commonly AR_ERROR_ILLEGAL_STATE: recording must be configured before session.resume().
            Timber.w(e, "eval: startRecording failed (recording must be set up at session resume)")
            null
        }
    }

    fun stopRecording(session: Session) {
        try {
            if (session.recordingStatus == RecordingStatus.OK) session.stopRecording()
        } catch (e: Exception) {
            Timber.w(e, "eval: stopRecording failed")
        }
    }

    /** Set a recorded dataset for playback. Session must be paused; caller resumes after. */
    fun startPlayback(session: Session, file: File): Boolean = try {
        session.setPlaybackDatasetUri(Uri.fromFile(file))
        true
    } catch (e: Exception) {
        Timber.w(e, "eval: startPlayback failed (set the dataset while the session is paused)")
        false
    }

    fun isPlaying(session: Session): Boolean = session.playbackStatus == PlaybackStatus.OK

    /**
     * DEPTH-REPLAY VALIDATION (spec risk): during playback, attempt to acquire a depth image and
     * report whether depth is present. If false, M2–M4 cost/effectiveness can only come from the
     * live telemetry path, not the bench. Call once after playback starts and a few frames elapse.
     */
    fun playbackHasDepth(session: Session): Boolean = try {
        session.update().acquireDepthImage16Bits().use { it.width > 0 }
    } catch (e: Exception) {
        Timber.w(e, "eval: playback depth unavailable")
        false
    }
}
