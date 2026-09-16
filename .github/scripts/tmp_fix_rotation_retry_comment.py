from pathlib import Path

path = Path("feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/rendering/ArRenderer.kt")
text = path.read_text()
old = """        // Bound on how many frames [overlayRotationCorrectionPending] is retried while its candidate
        // fails validation (anchor not yet TRACKING, or a degenerate up-vector fallback), before it is
        // accepted anyway with a warning logged. At a typical 30-60fps this is well under a second —
        // long enough for a freshly-created anchor to reach TRACKING, short enough that a persistently
        // non-tracking anchor doesn't leave the artwork's orientation undecided for the rest of the
        // session.
"""
new = """        // Bound on how many frames [overlayRotationCorrectionPending] is retried while its candidate
        // fails validation (anchor not yet TRACKING, or a degenerate up-vector fallback), before the
        // invalid candidate is discarded and the existing live correction is kept. At a typical
        // 30-60fps this is well under a second — long enough for a freshly-created anchor to reach
        // TRACKING, short enough that a persistently non-tracking anchor doesn't leave the artwork's
        // orientation undecided for the rest of the session.
"""
count = text.count(old)
if count != 1:
    raise SystemExit(f"expected exactly one stale retry-limit comment, found {count}")
path.write_text(text.replace(old, new, 1))
