from pathlib import Path

path = Path("feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/rendering/ArRenderer.kt")
text = path.read_text()
old = """     * Returns false when the GL thread stayed wedged inside the frame (e.g. blocked in
     * session.update() on a camera that never feeds); the @Volatile session is still nulled so
     * If the lock cannot be acquired within [timeoutMs], returns false without changing [session].
"""
new = """     * Returns false when the GL thread stayed wedged inside the frame (e.g. blocked in
     * session.update() on a camera that never feeds); in that case [session] is left untouched and
     * ownership stays with the renderer.
"""
count = text.count(old)
if count != 1:
    raise SystemExit(f"expected exactly one stale KDoc block, found {count}")
path.write_text(text.replace(old, new, 1))
