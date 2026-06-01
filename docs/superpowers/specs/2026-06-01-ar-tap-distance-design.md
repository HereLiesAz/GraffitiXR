# AR Tap-to-Distance (Sub-project C)

*Design spec — 2026-06-01*

## Context

Artists tap the wall marks they paint as anchors. This adds the camera→wall **distance** at those taps:
a live center-reticle readout plus a pinned distance label at each tapped mark, and — because a tapped
mark with a known range is a high-confidence metric point — each tap is also fed into the Sub-project B
pose fusion as a **support anchor**, tightening the consensus.

Reality (verified earlier this session):
- ARCore's Depth API yields a **full per-pixel depth map** (mono ML-derived; dual-lens hardware-stereo).
  `ArRenderer` already samples the **center** pixel into `ArUiState.currentCenterDepth` every frame
  (`ArRenderer.kt:~410`); the live reticle just needs to display it.
- On tap, the renderer already captures the whole depth buffer + intrinsics + view matrix into
  `ArUiState` (`targetDepthBuffer`, `targetDepthBufferWidth/Height`, `targetDepthStride`,
  `targetIntrinsics`, `targetCaptureViewMatrix`) — but nothing reads depth **at the tapped pixel**.
- `tapHighlightKeypoints: List<Pair<Float,Float>>` (normalized) is currently **stored but never
  rendered** — our chips give it a purpose.
- Robust screen→image-pixel mapping is `frame.transformCoordinates2d` (already used in
  `BackgroundRenderer.kt:91`), handling display rotation + crop. The current camera `frame` is only
  available on the GL thread.
- `isImperialUnits` already exists in `ArUiState` (m vs ft). `AnchorOrchestrator.addSupportAnchor(session,
  worldPose)` and `ArViewModel.arCoreHitTestToWorld(screenPoint)` already exist.

## Goals

- **Live reticle**: a small center readout of `currentCenterDepth`, unit-formatted, in `EditorMode.AR`.
- **Pinned per-tap distance**: on each tap, camera→point range at the tapped pixel, shown as a chip at
  that screen position; persists until cleared.
- **Units**: reuse `isImperialUnits` (m / ft).
- **Fusion constraint**: each confident tap also becomes a `addSupportAnchor` in B's fusion.
- Pure depth-lookup + formatting are **unit-tested**.

## Non-goals

- No new depth source; `OVERLAY` (CameraX) and 2D modes are out of scope (no depth).
- No change to the tap→capture target-creation flow beyond reading depth at the tap and pinning a label.

## Components

### Pure, unit-tested (`:feature:ar`)
1. `DistanceFormat.format(meters, imperial)` → `"2.3 m"` / `"7.5 ft"`; invalid (≤0) → `"—"`.
2. `DepthLookup.depthMetersAt(buffer, stride, depthW, depthH, u, v)` — `u,v` are image-normalized
   `[0,1]`; clamps to bounds, reads the 16-bit sample (`raw and 0x1FFF` mm → m), returns `-1f` for
   invalid/out-of-range (matching the existing `0 < mm < 7900` filter).

### State (`:core:common`)
3. `data class TapMark(val nx: Float, val ny: Float, val distanceMeters: Float)`. Migrate
   `ArUiState.tapHighlightKeypoints: List<Pair<Float,Float>>` → `tapMarks: List<TapMark>` (3 references:
   the decl, the add in `onTargetCaptured`, the clear in `clearTapHighlights`).

### Renderer (`:feature:ar`)
4. On the capture tick (GL thread, live `frame`), map the pending tap (`nx,ny` view-normalized) →
   image-normalized via `frame.transformCoordinates2d(VIEW_NORMALIZED → IMAGE_NORMALIZED)`, then
   `DepthLookup.depthMetersAt(...)`. Return the distance via the `onTargetCaptured` callback (new
   `tapDistanceMeters` param).

### ViewModel (`:feature:ar`)
5. Store the distance into the new `TapMark` alongside the existing keypoint add. Expose the reticle value
   (already in `currentCenterDepth`).
6. **Fusion constraint**: when a tap yields a valid distance and an anchor exists, call
   `arCoreHitTestToWorld(tapPixel)` and, if non-null, `anchorOrchestrator.addSupportAnchor(session,
   Pose(worldPoint))`. (The renderer owns `anchorOrchestrator`; expose a small
   `renderer.addTapSupportAnchor(nx, ny)` that does the hit-test + add on the GL thread, called from the
   capture path.)

### UI (`:app`)
7. Reticle: a centered label showing `format(currentCenterDepth, isImperialUnits)` when in
   `EditorMode.AR`, depth supported, not guest, not in a modal. Reuse the existing distance HUD style.
8. Chips: for each `TapMark`, a small label at `(nx*width, ny*height)` showing its formatted distance.

## Error / edge cases

- Invalid/out-of-range tapped depth → chip shows `"—"` (still pins the point); no support anchor added.
- Depth API unsupported (`isDepthApiSupported == false`) → no reticle; taps still work for capture.
- `transformCoordinates2d` / hit-test failure → null distance / no constraint; never crash the frame.
- Guest / modal open → reticle hidden (matches existing overlay gating).

## Testing

- **Unit**: `DistanceFormat` (m, ft, invalid); `DepthLookup.depthMetersAt` over a synthetic buffer
  (known stride/values; center vs corner; out-of-range → -1).
- **Build**: `:core:common`, `:feature:ar`, `:app` assemble.
- **On-device (deferred)**: tap a wall at a tape-measured distance; compare reticle + chip; confirm taps
  add support anchors (B fusion stability improves). Mono + dual-lens.

## Sequencing

This is **C** of A→B→C — the final slice. It depends on B's `AnchorOrchestrator` support-anchor path
(merged) and reuses the Sub-project A depth/units groundwork.
