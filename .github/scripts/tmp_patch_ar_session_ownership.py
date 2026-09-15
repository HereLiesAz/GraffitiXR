from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}")
    p.write_text(text.replace(old, new, 1))


renderer = "feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/rendering/ArRenderer.kt"
view_model = "feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/ArViewModel.kt"

replace_once(
    renderer,
    """     * no NEW frame starts, but the wedged frame holds its own stale reference — the caller must
     * rely on Session.close() to absorb the in-flight update(). Safe from any thread; never
     * blocks longer than [timeoutMs]. Does NOT set [isDestroying]: callers that are tearing down
""",
    """     * If the lock cannot be acquired within [timeoutMs], returns false without changing [session].
     * A timeout means ownership was not transferred; callers must not touch the ARCore Session until
     * a later successful handoff. Safe from any thread; never blocks longer than [timeoutMs].
     * Does NOT set [isDestroying]: callers that are tearing down
""",
)

replace_once(
    renderer,
    """    fun detachSessionBounded(timeoutMs: Long): Boolean {
        val locked = try {
            sessionLock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
        try {
            session = null
        } finally {
            if (locked) sessionLock.unlock()
        }
        return locked
    }
""",
    """    fun detachSessionBounded(timeoutMs: Long): Boolean {
        val locked = try {
            sessionLock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
        if (!locked) return false
        try {
            session = null
        } finally {
            sessionLock.unlock()
        }
        return true
    }
""",
)

replace_once(
    view_model,
    """            // its MTC_vio thread. detachSessionBounded confirms the GL thread is out of the frame
            // (or times out if it's wedged inside update(), in which case close() absorbs the
            // in-flight call). Saves still work: they read the renderer's sub-renderers, not the
            // ARCore session.
""",
    """            // its MTC_vio thread. detachSessionBounded is a real ownership handoff: success means
            // the GL thread is out of the frame; timeout means it still owns the Session and native
            // teardown must be deferred. Saves still work: they read the renderer's sub-renderers,
            // not the ARCore session.
""",
)

replace_once(
    view_model,
    """            renderer?.isDestroying = true
            val glThreadOut = renderer?.detachSessionBounded(1500L) ?: true
            if (!glThreadOut) {
                appendDiag("cleanup: GL thread wedged in-frame after 1500ms — closing session anyway")
            }
            saveMapBlocking()
            session?.let {
                if (isSessionResumed) it.pause()
                it.close()
            }
            session = null
            renderer = null
            isSessionResumed = false
            _isCameraInUseByAr.value = false
            isDestroying = false
            clearCaptureState()
""",
    """            val r = renderer
            val s = session
            val wasResumed = isSessionResumed
            r?.isDestroying = true
            val handoffSucceeded = r?.detachSessionBounded(1500L) ?: true

            saveMapBlocking()
            when (decideSessionOwnership(handoffSucceeded, SessionOwnershipAction.CLEANUP)) {
                SessionOwnershipDecision.PROCEED -> {
                    s?.let { closeDetachedArSession(it, wasResumed) }
                }
                SessionOwnershipDecision.DEFER_CLEANUP -> {
                    appendDiag("cleanup: GL thread still owns ARCore after 1500ms — deferring native close")
                    if (r != null && s != null) {
                        deferArSessionCloseUntilRendererHandoff(r, s, wasResumed)
                    }
                }
                SessionOwnershipDecision.ABORT_RECONFIGURE -> {
                    appendDiag("cleanup: ownership policy aborted native teardown")
                }
            }
            session = null
            renderer = null
            isSessionResumed = false
            _isCameraInUseByAr.value = false
            isDestroying = false
            clearCaptureState()
""",
)

replace_once(
    view_model,
    """                val wasResumed = isSessionResumed
                // Get the GL thread out of the frame body before touching the session config.
                val glThreadOut = renderer?.detachSessionBounded(1500L) ?: true
                if (!glThreadOut) {
                    appendDiag("stereo recovery: GL thread wedged in-frame after 1500ms — reconfiguring anyway")
                }
                if (wasResumed) pauseArSessionInternal()
""",
    """                val wasResumed = isSessionResumed
                // Get the GL thread out of the frame body before touching the session config.
                val handoffSucceeded = renderer?.detachSessionBounded(1500L) ?: true
                if (
                    decideSessionOwnership(handoffSucceeded, SessionOwnershipAction.RECONFIGURE) ==
                    SessionOwnershipDecision.ABORT_RECONFIGURE
                ) {
                    appendDiag("stereo recovery: GL thread still owns ARCore after 1500ms — aborting live reconfigure")
                    _feedback.tryEmit(
                        com.hereliesaz.graffitixr.common.model.FeedbackEvent.Error(
                            "AR camera recovery timed out safely — exit and re-enter AR"
                        )
                    )
                    return@withLock
                }
                if (wasResumed) pauseArSessionInternal()
""",
)
