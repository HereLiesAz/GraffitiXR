# Doodle build: 5-10 min wait

## Problem

The doodle builder's close-up detection (`CloseUpFeatures.detect`) takes 5-10 minutes. There is no
foreground service, so the user must keep the app open and the target in view for the whole build.
Not measured on device; the cost model below is from reading the code.

## Findings (source, not measured)

- One SuperPoint (OpenCV DNN, CPU) inference per crop, sequential, on one thread. `SuperPointDetector`
  holds a mutex over one net, so more threads only queue.
- Crop count: 13 when the wall is unmeasured, 49 when the capture distance is known
  (`maxZoomFor` lifts the zoom cap from 3 to 6; the zoom-6 grid is 36 crops). Measuring the wall
  makes the build ~4x heavier.
- Crops are scaled to 960 px (`MAX_VIEW_SIDE`); `SuperPointDetector::kMaxInputSide` is 640, so the
  extra pixels are discarded before inference.
- Each crop contributes at most `perViewBudget` features (~30 at 49 crops). Crops over blank wall
  contribute ~none but cost a full inference.
- `buildSingle` works from snapshot inputs only (bitmap, glView, intr, plane, anchorModel); nothing
  live is read during the close-up phase. Results are held in memory (`parts`) until the end:
  an interruption (backgrounded, killed) loses everything.
- `onQuickSet` already installs base + zoom-2 crops (4) early.

## Open questions

- [ ] Progress pill total: 13 or 49 (measured vs unmeasured wall)?
- [ ] "Useless if the fingerprint leaves view": is this the ARCore world frame resetting or drifting
      during the build (final `ingestSingle` places points in the capture-time world frame)?
      Confirm before designing the re-mapping.
- [ ] Is the user blocked until the whole pill finishes, or only until the quick set?

## Plan (check in before implementing)

Goal: usable in seconds, finished within the user's attention, safe to interrupt.

1. [ ] Skip crops with too little base-feature coverage (blank wall). Pure geometry: unit-testable.
2. [ ] Scale crops to at most 640 px (match `kMaxInputSide`).
3. [ ] Zoom-6 grid: sparse or lazy (only the cell the phone aims at, when near the wall).
4. [ ] Checkpoint per-crop features to disk, keyed on photo hash + settings; resume instead of
      restart after an interruption.
5. [ ] Treat the quick set as "done" for the UI; the tail runs while the app is in use, never as a
      blocking wait.
6. [ ] Decouple the tail from the capture-time world frame: attach close-ups in the page/photo frame
      through the doodle's own placement, so tracking loss or a world reset does not strand them.
7. [ ] Longer shot, only after 1-6: int8/fp16 SuperPoint or another DNN backend.

## Verification

- JVM tests for `CloseUpFeatures` selection (coverage skip, size cap, view count by zoom).
- On-device: `DoodleBuildProgress` timings before/after for a measured and an unmeasured wall; the
  `ARDIAG` lines. Needs a device; not possible in this container (no Android SDK).
