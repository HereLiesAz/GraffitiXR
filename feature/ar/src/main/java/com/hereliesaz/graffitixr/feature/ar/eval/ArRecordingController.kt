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

    /** Start recording the live session to an MP4 dataset. Returns the target file. */
    fun startRecording(session: Session, name: String): File {
        val file = File(recordingsDir(), "$name.mp4")
        val config = RecordingConfig(session).setMp4DatasetUri(Uri.fromFile(file))
        session.startRecording(config)
        Timber.i("eval: recording -> ${file.absolutePath}")
        return file
    }

    fun stopRecording(session: Session) {
        if (session.recordingStatus == RecordingStatus.OK) session.stopRecording()
    }

    /** Set a recorded dataset for playback. Session must be paused; caller resumes after. */
    fun startPlayback(session: Session, file: File) {
        session.setPlaybackDatasetUri(Uri.fromFile(file))
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
