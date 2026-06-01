# AR Method Drift & Cost Evaluation Harness (Sub-project A)

*Design spec — 2026-06-01*

## Context

GraffitiXR keeps a sketch overlay locked to a wall using the artist's own marks as a persistent
anchor (a repurposed *grid method*). Four mechanisms each attack drift a different way:

| Mechanism | Anti-drift role | Needs |
|---|---|---|
| **M1 — ARCore VIO / anchor** (`CLOUD_POINTS`) | smooth high-rate tracking between observations | nothing beyond ARCore |
| **M2 — Voxel-reloc** (`VOXEL_HASH`, Persistent Voxel Memory + OpenCV fingerprints) | global snap-back / recovery after loss | Depth API |
| **M3 — Surface-mesh** (`SURFACE_MESH`) | constrains the overlay to real surface geometry | Depth API |
| **M4 — Cloud-offset** (`CLOUD_OFFSET`) | holds a fixed offset across large translations | Depth API |

Today these are **mutually-exclusive user choices** in Settings, and the rendered pose comes from a
**hard toggle**, not a fusion: before the anchor is established the overlay pose is the native
voxel/depth pose (`SlamManager.getAnchorTransform()`), and after it's established it is *entirely* the
ARCore-anchor consensus (`AnchorOrchestrator.getConsensusMatrix()`, `ArRenderer.kt:391-396`). The
native snap-back (voxel-reloc + mark-PnP) and the ARCore consensus never actually combine.

The end goal (separate specs) is to **merge** the mechanisms into one confidence-weighted consensus
that drives drift toward zero (Sub-project B), with tap-to-distance folded in (Sub-project C). But the
user added a hard requirement: first **find out which mechanisms actually work and which cost too much
battery/compute**, so the fusion only carries its weight. This spec covers **only that measurement
step (A)** — its output (a keep/drop recommendation per mechanism) is the input that decides B's
method set.

## Goals

- Produce **comparable, per-mechanism** effectiveness and cost numbers on both device classes: a
  **dual-lens** device (mandatory whenever the device has two back cameras) and a **monocular** device
  (mandatory fallback when it doesn't). Both classes must be supported and pushed to their best
  achievable accuracy.
- Two acquisition paths: a **live dev telemetry overlay** (real walls) and a **deterministic bench
  harness** (repeatable A/B).
- Emit a **decision artifact** ranking mechanisms by *effectiveness first* and flagging redundancy.

## Guiding principle: accuracy is paramount

There is no LiDAR on these devices, so drift accuracy is hard-won and is the product's whole value —
a subpar, drifting overlay is useless. Therefore **cost (battery/compute) never outranks
effectiveness.** The harness measures cost so we can understand it and optimize, **not** to justify
trading away accuracy. A mechanism may be dropped from the later fusion only if it is **redundant** —
it adds no unique failure-mode coverage the survivors already provide — never merely because it is
expensive. Every retained device class (mono and dual-lens) is driven to its best available option.

## Non-goals

- No fusion, no pruning of the mechanisms themselves, no UX change to mode selection — those are
  Sub-project B. A only *measures*.
- No new anchoring algorithm. Mark-PnP/teleological is used as a measurement reference, not modified.

## Reference of truth

When the wall marks are visible, the **mark-based OpenCV PnP** pose (the Teleological SLAM path) is
treated as ground truth. Every candidate mechanism's pose is scored against it. Frames where marks are
not visible are excluded from *error* metrics (but still counted for *availability* and *cost*).

## Metrics

**Effectiveness (per mechanism, only while marks visible for error):**
- **Pose error** vs mark-PnP truth — translation (mm) and rotation (deg).
- **Jitter** — stddev of the mechanism's pose over a stationary window.
- **Recovery time** — ms from an *induced* tracking-loss (camera-cover / screen-off / fast pan, via a
  manual trigger in the overlay) to re-lock within a tolerance.
- **Availability** — % of frames the mechanism produces a usable estimate on that hardware (e.g. M2–M4
  are 0% on a no-Depth-API device).

**Cost (per mechanism):**
- **Native stage wall-time** — accumulating timers around `mVoxelHash.update`, `mSurfaceMesh.update`,
  PnP, and `draw` in `MobileGS.cpp`; exposed as average ms/stage.
- **Frame-time / FPS delta** — with the stage enabled vs disabled (A/B).
- **CPU%** — `Debug`/process stats sampled over the session.
- **Memory** — voxel count, mesh vertex count, native + heap footprint.
- **Battery** — `BatteryManager.BATTERY_PROPERTY_CURRENT_NOW` (mAh/min) plus thermal state, over a
  **fixed-duration, fixed-brightness, airplane-mode** session. Reported as ranges (battery deltas are
  noisy).

**Attribution:** per-mechanism cost requires **A/B toggling** each native stage on/off, which only
yields clean diffs on **deterministic input** — hence the bench harness below.

## Architecture / components

1. **Native instrumentation** — `:core:nativebridge`
   - Lightweight accumulating timers around each stage in `MobileGS.cpp` (zero work when the stage is
     disabled). New `MobileGS.h` fields for per-stage accumulated time + sample count.
   - Per-stage **enable flags** so the harness can A/B a stage independent of the mode enum.
   - JNI: `nativeGetStageTimings(): float[]` (avg ms per stage) and `nativeSetStageEnabled(stage, on)`
     in `GraffitiJNI.cpp`; mirrored in `SlamManager.kt` as `getStageTimings()` / `setStageEnabled()`.

2. **`DriftCostProbe`** — `:feature:ar` (pure + testable core)
   - Pure functions: `poseError(candidate, truth) -> (mm, deg)`, `jitter(window) -> stddev`,
     `availability(frames) -> ratio`, `recoveryMs(events) -> ms`. No Android deps → unit-testable with
     synthetic pose streams.
   - A thin collector wired **throttled** into the `ArRenderer` frame loop (reuse the existing
     `frameCount % 4` 15 Hz cadence at `ArRenderer.kt:399`) that each tick reads: active/fused pose,
     mark-PnP truth (if available), ARCore anchor pose, native stage timings, and samples
     CPU/battery/temp/memory. Buffers to a ring and appends to a session log.
   - **Session log**: CSV (one row/sample) + a JSON summary, written to app files dir; adb-pullable.

3. **Record/playback controller** — `:feature:ar`
   - Wraps the **ARCore Recording & Playback API** (`Session.startRecording` / `setPlaybackDataset`):
     record one real wall session once; replay it deterministically against each stage config.
   - **Risk to validate early:** confirm playback replays the depth frames
     (`acquireDepthImage16Bits`) and point cloud the pipeline relies on — if depth isn't in the
     recording, the bench can only cover M1, and M2–M4 cost/effectiveness stay telemetry-only.

4. **Dev diagnostics overlay** — `:app`
   - Gated behind a **dev flag** (build-config / hidden setting), extending the existing diagnostic
     overlay (`MainActivity.kt:2363` area). Shows live per-mechanism error/jitter/recovery +
     frame-time/CPU/battery, an **induce-tracking-loss** button, and **start/stop recording**.

5. **Decision artifact**
   - A generated summary (markdown/JSON) that ranks each mechanism by **effectiveness first**
     (pose error / jitter / recovery / availability) and reports cost **alongside**, not as a divisor.
     The keep/drop recommendation per mechanism applies the guiding principle: drop **only** a
     mechanism that is **redundant** (no unique failure-mode coverage); keep an accurate,
     uniquely-covering mechanism regardless of cost. Output feeds Sub-project B.
   - **No pre-pruning:** all four mechanisms are measured. Hypotheses (e.g. M4/Cloud-offset may be a
     stub that just falls back to the voxel renderer; M3/Surface-mesh may only matter on non-flat
     walls) are things the data must confirm or refute — never a reason to skip measuring a mechanism.

## Testing

- **Unit** (`:feature:ar`): `DriftCostProbe` pure math — `poseError`, `jitter`, `availability`,
  `recoveryMs` — against synthetic pose streams with known answers.
- **Instrumented/manual**: timing, CPU, and battery sampling validated on-device; the
  record→replay→A/B loop produces a consistent comparison table across two runs of the same dataset.
- **No-Depth-API device**: confirm M2–M4 report 0% availability rather than feeding never-arriving
  depth (a real bug the current code has — see `README`/banner at `MainActivity.kt:686`).

## Risks / open questions

- **ARCore playback + depth** — primary feasibility risk (see component 3). Validate before relying on
  the bench for M2–M4 cost.
- **Battery measurement noise** — mitigate with fixed duration/brightness/airplane-mode; report ranges
  not point values.
- **Probe overhead** — the probe itself must be cheap and dev-flag-gated so it doesn't distort the cost
  numbers it's measuring.
- **Mark-visibility coverage** — error metrics only exist while marks are visible; long painting
  sessions (marks covered) lean on jitter/recovery/availability instead.

## Sequencing

This is **A** of **A → B → C**. A produces the keep/drop data; **B** (separate spec) promotes
`AnchorOrchestrator` into a confidence-weighted pose-fusion of the surviving mechanisms (replacing the
`ArRenderer.kt:391-396` toggle); **C** (separate spec) folds in tap-to-distance as both a metric anchor
constraint and the per-tap distance readout originally requested.
