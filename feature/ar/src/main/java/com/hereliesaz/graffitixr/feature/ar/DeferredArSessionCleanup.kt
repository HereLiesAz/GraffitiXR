package com.hereliesaz.graffitixr.feature.ar

import com.google.ar.core.Session
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer
import timber.log.Timber

/**
 * Pause/close a session only after renderer ownership has already been handed off.
 *
 * Each native call is guarded independently so a pause failure cannot suppress the final close.
 */
internal fun closeDetachedArSession(session: Session, wasResumed: Boolean) {
    if (wasResumed) {
        try {
            session.pause()
        } catch (e: Exception) {
            Timber.w(e, "AR session pause during cleanup failed")
        }
    }
    try {
        session.close()
    } catch (e: Exception) {
        Timber.w(e, "AR session close during cleanup failed")
    }
}

/**
 * Finish teardown without ever closing underneath an in-flight GL-thread ARCore call.
 *
 * This deliberately uses an independent daemon thread rather than viewModelScope: ViewModel cleanup
 * can cancel that scope while a native frame is still wedged. The thread retries the bounded renderer
 * handoff until the session lock is genuinely acquired. If the native call never returns, the daemon
 * remains parked and the stuck session is abandoned rather than invoking undefined concurrent native
 * teardown.
 */
internal fun deferArSessionCloseUntilRendererHandoff(
    renderer: ArRenderer,
    session: Session,
    wasResumed: Boolean,
    handoffTimeoutMs: Long = 1_500L,
) {
    Thread {
        while (!Thread.currentThread().isInterrupted) {
            if (renderer.detachSessionBounded(handoffTimeoutMs)) {
                closeDetachedArSession(session, wasResumed)
                return@Thread
            }
        }
    }.apply {
        isDaemon = true
        name = "ArSessionDeferredClose"
        start()
    }
}
