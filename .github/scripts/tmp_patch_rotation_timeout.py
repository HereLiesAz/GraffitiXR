from pathlib import Path

path = Path("feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/rendering/ArRenderer.kt")
text = path.read_text()

old_comment = """    // candidate correction failed validation. Reset whenever a NEW establishment arms the pending
    // flag, and again once a candidate is accepted. Capped by MAX_OVERLAY_CORRECTION_RETRY_FRAMES so
    // an anchor that never starts tracking doesn't leave the flag pending for the rest of the session.
"""
new_comment = """    // candidate correction failed validation. Reset whenever a NEW establishment arms the pending
    // flag, and again once the candidate is either applied or discarded. Capped by
    // MAX_OVERLAY_CORRECTION_RETRY_FRAMES so an anchor that never starts tracking doesn't leave the
    // flag pending for the rest of the session.
"""

old_block = """                val anchorTracking = normalIsDegenerate || activeAnchorCount() > 0
                val giveUp = overlayRotationCorrectionRetryFrames >= MAX_OVERLAY_CORRECTION_RETRY_FRAMES
                val valid = anchorTracking && reproducesNormal

                if (valid || giveUp) {
                    if (giveUp && !valid) {
                        Timber.w(
                            \"ARDIAG overlayRotationCorrection: accepting an unvalidated capture after \" +
                                \"$overlayRotationCorrectionRetryFrames retries \" +
                                \"(anchorTracking=$anchorTracking reproducesNormal=$reproducesNormal)\"
                        )
                    }
                    System.arraycopy(overlayRotationCorrectionCandidate, 0, overlayRotationCorrection, 0, 16)
                    overlayRotationCorrectionPending = false
                    overlayRotationCorrectionRetryFrames = 0
                } else {
                    // Leave [overlayRotationCorrectionPending] set: retry against next frame's
                    // anchorMatrix, which is more likely to be the real anchor's own tracked pose.
                    // overlayRotationCorrection is untouched — it stays whatever it was (identity, on
                    // the very first attempt), which draws as \"use the raw anchor frame\" while retrying.
                    overlayRotationCorrectionRetryFrames++
                }
"""
new_block = """                val anchorTracking = normalIsDegenerate || activeAnchorCount() > 0
                val valid = anchorTracking && reproducesNormal

                when (
                    decideOverlayRotationCorrection(
                        valid = valid,
                        retryFrames = overlayRotationCorrectionRetryFrames,
                        maxRetryFrames = MAX_OVERLAY_CORRECTION_RETRY_FRAMES,
                    )
                ) {
                    OverlayRotationCorrectionDecision.APPLY -> {
                        System.arraycopy(overlayRotationCorrectionCandidate, 0, overlayRotationCorrection, 0, 16)
                        overlayRotationCorrectionPending = false
                        overlayRotationCorrectionRetryFrames = 0
                    }
                    OverlayRotationCorrectionDecision.RETRY -> {
                        // Leave [overlayRotationCorrectionPending] set: retry against next frame's
                        // anchorMatrix, which is more likely to be the real anchor's own tracked pose.
                        // overlayRotationCorrection is untouched — it stays whatever it was (identity, on
                        // the very first attempt), which draws as \"use the raw anchor frame\" while retrying.
                        overlayRotationCorrectionRetryFrames++
                    }
                    OverlayRotationCorrectionDecision.DISCARD -> {
                        Timber.w(
                            \"ARDIAG overlayRotationCorrection: discarding invalid capture after \" +
                                \"$overlayRotationCorrectionRetryFrames retries \" +
                                \"(anchorTracking=$anchorTracking reproducesNormal=$reproducesNormal)\"
                        )
                        // A timeout is not validation. Keep the existing live correction unchanged and
                        // stop retrying until the next anchor establishment explicitly arms a new capture.
                        overlayRotationCorrectionPending = false
                        overlayRotationCorrectionRetryFrames = 0
                    }
                }
"""

for old, new, label in [
    (old_comment, new_comment, "retry comment"),
    (old_block, new_block, "rotation decision block"),
]:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"expected exactly one {label}, found {count}")
    text = text.replace(old, new, 1)

path.write_text(text)
