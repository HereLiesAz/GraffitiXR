# AR Tap-to-Distance — Implementation Plan (Sub-project C)

> Use superpowers:subagent-driven-development or executing-plans. Steps use `- [ ]`.

**Goal:** Show camera→wall distance — a live center reticle + a pinned label at each tapped mark (m/ft) — and feed each confident tap into the Sub-project B fusion as a support anchor.

**Architecture:** Pure, unit-tested `DistanceFormat` + `DepthLookup` do the math. The renderer maps a tap to an image-normalized depth pixel via ARCore `transformCoordinates2d` (GL thread) and returns the range through `onTargetCaptured`. `ArUiState` carries `TapMark(nx,ny,distanceMeters)`. The overlay renders the reticle + per-tap chips. Taps also call `addSupportAnchor` to strengthen B's consensus.

**Tech Stack:** Kotlin, ARCore, Compose, JUnit.

**Spec:** `docs/superpowers/specs/2026-06-01-ar-tap-distance-design.md`

**Verified sites:** `ArUiState.currentCenterDepth`, `tapHighlightKeypoints` (decl + add in `onTargetCaptured` + clear in `clearTapHighlights`), `isImperialUnits`; `ArRenderer` center-depth sample (~410) + `onTargetCaptured` callback; `BackgroundRenderer.kt:91` (`transformCoordinates2d`); `AnchorOrchestrator.addSupportAnchor`; `ArViewModel.arCoreHitTestToWorld`. Depth format: `raw and 0x1FFF` mm, valid `0 < mm < 7900`.

---

## Task 1: DistanceFormat (pure, TDD)

**Files:** Create `feature/ar/.../eval/DistanceFormat.kt`; Test `.../eval/DistanceFormatTest.kt`.
(Place in the existing `eval` package — shared formatting utility.)

- [ ] **Step 1: failing test**
```kotlin
package com.hereliesaz.graffitixr.feature.ar.eval
import org.junit.Assert.assertEquals
import org.junit.Test
class DistanceFormatTest {
    @Test fun `metric`() { assertEquals("2.3 m", DistanceFormat.format(2.34f, imperial = false)) }
    @Test fun `imperial converts meters to feet`() { assertEquals("7.5 ft", DistanceFormat.format(2.286f, imperial = true)) } // 2.286 m = 7.50 ft
    @Test fun `invalid is dash`() {
        assertEquals("—", DistanceFormat.format(0f, false)); assertEquals("—", DistanceFormat.format(-1f, true))
    }
}
```
- [ ] **Step 2: run → FAIL** `sh gradlew --offline :feature:ar:testDebugUnitTest --tests "*DistanceFormatTest"`
- [ ] **Step 3: implement**
```kotlin
package com.hereliesaz.graffitixr.feature.ar.eval
object DistanceFormat {
    private const val FEET_PER_METER = 3.28084f
    fun format(meters: Float, imperial: Boolean): String {
        if (meters <= 0f) return "—"
        return if (imperial) "%.1f ft".format(meters * FEET_PER_METER) else "%.1f m".format(meters)
    }
}
```
- [ ] **Step 4: run → PASS** ; **Step 5: commit** `feat(ar-tap): distance formatter with tests`

---

## Task 2: DepthLookup (pure, TDD)

**Files:** Create `feature/ar/.../eval/DepthLookup.kt`; Test `.../eval/DepthLookupTest.kt`.

- [ ] **Step 1: failing test** — synthetic 2x2 16-bit depth buffer, stride = width*2.
```kotlin
package com.hereliesaz.graffitixr.feature.ar.eval
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
class DepthLookupTest {
    private fun buf(vararg mm: Short): ByteBuffer {
        val b = ByteBuffer.allocateDirect(mm.size * 2).order(ByteOrder.nativeOrder())
        mm.forEach { b.putShort(it) }; b.position(0); return b
    }
    @Test fun `reads mm to meters at a pixel`() {
        // 2x2, stride=4 bytes/row. pixels: (0,0)=1000mm (1,0)=2000 (0,1)=3000 (1,1)=4000
        val b = buf(1000, 2000, 3000, 4000)
        assertEquals(1.0f, DepthLookup.depthMetersAt(b, stride = 4, depthW = 2, depthH = 2, u = 0.0f, v = 0.0f), 1e-3f)
        assertEquals(4.0f, DepthLookup.depthMetersAt(b, 4, 2, 2, u = 0.99f, v = 0.99f), 1e-3f)
    }
    @Test fun `out of range yields -1`() {
        val b = buf(0, 8000, 0, 0) // 0 invalid, 8000 > 7900 invalid
        assertEquals(-1f, DepthLookup.depthMetersAt(b, 4, 2, 2, 0f, 0f), 1e-3f)
        assertEquals(-1f, DepthLookup.depthMetersAt(b, 4, 2, 2, 0.99f, 0f), 1e-3f)
    }
}
```
- [ ] **Step 2: run → FAIL**
- [ ] **Step 3: implement**
```kotlin
package com.hereliesaz.graffitixr.feature.ar.eval
import java.nio.ByteBuffer
object DepthLookup {
    /** @param u,v image-normalized [0,1]. Returns meters, or -1f if invalid/out-of-range. */
    fun depthMetersAt(buffer: ByteBuffer, stride: Int, depthW: Int, depthH: Int, u: Float, v: Float): Float {
        if (depthW <= 0 || depthH <= 0) return -1f
        val x = (u.coerceIn(0f, 1f) * depthW).toInt().coerceIn(0, depthW - 1)
        val y = (v.coerceIn(0f, 1f) * depthH).toInt().coerceIn(0, depthH - 1)
        val off = y * stride + x * 2
        if (off < 0 || off + 2 > buffer.limit()) return -1f
        val raw = buffer.getShort(off).toInt() and 0xFFFF
        val mm = raw and 0x1FFF
        return if (mm in 1..7899) mm / 1000f else -1f
    }
}
```
- [ ] **Step 4: run → PASS** ; **Step 5: commit** `feat(ar-tap): tapped-pixel depth lookup with tests`

---

## Task 3: TapMark in state

**Files:** `core/common/.../model/UiState.kt`.

- [ ] Add `data class TapMark(val nx: Float, val ny: Float, val distanceMeters: Float)`. Replace
  `tapHighlightKeypoints: List<Pair<Float, Float>>` with `tapMarks: List<TapMark> = emptyList()`.
- [ ] Build `:core:common:assembleDebug`. (Compile will flag the 3 `ArViewModel` references — fixed in Task 4.)
- [ ] Commit `feat(ar-tap): TapMark state model`.

## Task 4: Renderer + ViewModel wiring

**Files:** `ArRenderer.kt`, `ArViewModel.kt`.

- [ ] Renderer: in the capture path where `onTargetCaptured` is invoked (GL thread, live `frame`),
  before invoking the callback, if a pending tap exists, compute
  `val out = FloatArray(2); frame.transformCoordinates2d(Coordinates2d.VIEW_NORMALIZED, floatArrayOf(nx, ny), Coordinates2d.IMAGE_NORMALIZED, out)`
  then `val dist = DepthLookup.depthMetersAt(depthBuffer, depthStride, depthW, depthH, out[0], out[1])`.
  Add `tapDistanceMeters: Float` to the `onTargetCaptured` callback signature and pass `dist`.
- [ ] Renderer: add `fun addTapSupportAnchor(nx: Float, ny: Float)` — on the GL thread, hit-test the tap
  (reuse the existing `latestFrame`/hit-test used by `arCoreHitTestToWorld`) and
  `anchorOrchestrator.addSupportAnchor(session, hitPose)` when a hit exists and an anchor is established.
- [ ] ViewModel: in `onTargetCaptured`, replace the `tapHighlightKeypoints + tapPos` add with
  `tapMarks + TapMark(tapPos.first, tapPos.second, tapDistanceMeters)`; update `clearTapHighlights` to
  clear `tapMarks`. After storing, if `tapDistanceMeters > 0`, call `renderer?.addTapSupportAnchor(nx, ny)`.
- [ ] Build `:feature:ar:assembleDebug`. Commit `feat(ar-tap): depth-at-tap + support-anchor wiring`.

## Task 5: Overlay reticle + chips

**Files:** `app/.../MainActivity.kt`.

- [ ] Reticle: where AR overlays render (gated `editorMode == EditorMode.AR && !showLibrary && !showSettings && arUiState.isDepthApiSupported`), draw a centered `Text(DistanceFormat.format(arUiState.currentCenterDepth, arUiState.isImperialUnits))`.
- [ ] Chips: `arUiState.tapMarks.forEach { m -> }` place a small `Text(DistanceFormat.format(m.distanceMeters, arUiState.isImperialUnits))` offset to `(m.nx*width, m.ny*height)` (use the same `fullSize`/Box used by `OffscreenIndicators`).
- [ ] Build `:app:assembleDebug`. Commit `feat(ar-tap): reticle + per-tap distance chips`.

## Final verification
- [ ] `sh gradlew --offline :feature:ar:testDebugUnitTest --tests "*DistanceFormatTest" --tests "*DepthLookupTest"` → PASS.
- [ ] `:core:common`, `:feature:ar`, `:app` assemble; full `:feature:ar` test suite shows only the 3 known pre-existing failures.
- [ ] On-device (deferred): tap a tape-measured wall; verify reticle + chip; confirm taps add support anchors.

## Notes
- Reuses Sub-project A's `eval` package for the pure utilities. Depends on B's `addSupportAnchor` (merged).
- If `transformCoordinates2d` enum names differ in the pinned ARCore version, match `BackgroundRenderer.kt:91`'s usage.
