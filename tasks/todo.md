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

1. [x] Skip crops over wall where the capture's own detection found nothing (6x crops only); densest first. `CloseUpFeatures.prioritized`.
2. [x] SuperPoint crops at most 640 px (`SUPERPOINT_VIEW_SIDE`); ORB keeps 960. Housekeeping: SuperPoint already downsized inside, so the DNN cost is unchanged.
3. [~] Zoom-6 grid: bounded by a 45 s clock after the quick set (`FineBudget`), not made lazy. Crops cut by the clock are dropped, not deferred: a lazy aim-driven step has to re-install the doodle from the capture-time snapshot (ARCore world frame), which is the open world-frame question. Do with item 6.
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

## Review (items 1-3)

- 49 crops (measured wall) -> quick set (4) + zoom 3 (9) + only the zoom-6 crops that hold marks, all
  inside a 45 s clock after the quick set. Default call (no base positions, no clock) still runs 49.
- Verified on the JVM against the real `CloseUpFeatures.kt` and `CloseUpFeaturesTest.kt` (11 tests, the
  5 new ones fail when blank crops are kept), plus an ad-hoc run of `detect` with a fake clock and
  stubbed `Bitmap`: 18 detections with 5 marked cells, 7 with a 25 ms clock at 10 ms per crop,
  quick set handed off after 4, progress cleared, every crop 640 px.
- Not verified: Android build (no SDK here), `MetricDoodleBuilder` / `StandaloneFingerprintBuilder`
  call sites compile, any timing on a device. The clock (45 s) is a guess to tune on a device.
- Trade: a measured wall on a slow phone loses the tail of the zoom-6 crops. Mark-dense crops go
  first, so what is cut is the least dense.

# Marks: black text on white not highlighted (dark scene)

Report (screenshot, AR target review): white text on red is highlighted, black text on white is not.

- Cause: `markMap` picks one polarity for the whole frame. Median luma < 96 means "marks are light";
  a dark mark then also has to be darker than the frame's median minus 25. Black print on a white flyer
  in a dark bar is lighter than the bar, so it fails that test. Only the paper's edge band (where the
  w/16 window puts it above its local average) lights up.
- Fix: `litPatchStrength` (dark scenes only): print is a pixel well below the "patch layer" it sits on,
  the closing of luma by a stroke-sized window then the opening by a letter-exceeding window, where
  that layer is lighter than the surface. Halo, thick-letter counter and the gaps between bright
  letters are 0 by construction. It must stand clear of the frame's noise (4 sigma): without that,
  uniform dark noise added ~105k false marks (found by comparing against the original loop).
- Verified on the JVM (real `ImageExt.kt`, stubbed `Bitmap`): 25 tests (existing 15 + 10 new); the
  flyer test finds 0 of 7680 print pixels with the rule off; markMap equals the original loop exactly
  on a light scene, a dark chalk scene, dark noise and light noise; 116 ms for 1633x1224 on a desktop JVM.
- Not verified: the real photo (only a synthetic flyer and coaster), the Android build, speed on a phone.
- Not changed: the paper's own edge band is still highlighted (pre-existing, the same in the screenshot);
  the mirror case (white print on a dark patch in a light scene) is untouched.

# Target review: show the CV's keypoints, and the cap-before-mask question

- Done: the review draws the detector's keypoints over the photo, pink where the marks mask keeps them,
  white where it drops them, with both counts (no words, so no new strings). `detectReviewKeypoints`
  (ArViewModel) runs the builder's own detector once per capture, off the main thread. The classification is
  `DoodleKeypointPreview.split` (4 JVM tests). The Compose and view-model wiring is unbuilt (no Android SDK here).
- Finding, for "should relocalization use ALL SuperPoint and ORB keypoints?": the builders detect over the
  whole photo with the detector's default cap (SuperPoint `maxKps` 500, ORB 1500), THEN drop what falls
  outside the marks mask (`filterByMask` / `applyMask`). `SuperPointDetector::detect` documents this exact
  trap for its own masked overload ("the top-K is chosen from the WHOLE FRAME, and the mask then keeps
  only whichever of those survive") and avoids it there; the Kotlin builders go through the unmasked
  `nativeDetectSuperPoint` and reintroduce it. So the marks keep only the share of the top 500/1500 that
  happens to land on them. The overlay's two counts measure it on a real capture.
- Not "all": the live matcher is brute-force knnMatch, cost ~ doodle rows x live rows (256-float rows for
  SuperPoint), and low-score keypoints repeat worst across viewpoint (see `kDefaultScoreThresh`). The change
  to consider is "mask first, cap after" (uncapped candidates, mask, then the budget), not removing the cap.
  Not done: it changes relocalization behaviour and wants a device measurement first.

# First-run scribble: curated glyphs, fitted to the artwork

- Measured (not guessed): per-character keypoints with the app's own SuperPoint/ORB reproduction, black marks
  on a pale wall, two glyph sizes, three rotations. SuperPoint 2-32 per glyph (median 10), ORB repeatability under
  tilt 0.08 (`O`) to 0.54 (`#`). A random scribble: median ~50 SuperPoint keypoints, p5 ~33; from the pool: ~74, min 46.
  Never below the app's own 16-20 floor either way, so this is quality, not a fix for failures.
- Pool `#&m%t4f8@RaHX?`, one per look-alike group (rotation equivalents: M/W, +/x, 6/9...), `*` left out
  (small and raised in the app font, which the glyph is not centred for). Not used: the hint sentence about corners
  (needs 14 translations).
- Layout: `generate(aspect)`: grid chosen for the biggest cells on the canvas (a row for a wide box, a column for a
  tall one); `ScribbleFit` reads the subject's bounding box from a 96 px decode (opaque pixels of a cut-out, else
  what differs from the border colour; specks trimmed; 0.4..2.5). The scribble is generated after the pick and the
  draw screen waits for it; the seed is saved so rotating the phone keeps it.
- Verified on the JVM (real sources, 24 tests; 3 mutations each fail the test meant for them). Unbuilt: the Android
  wiring (`MainActivity` produceState, `ScribbleDrawScreen` layout, `ScribbleFitLoader`).
- Known limits: the subject is a bounding box, not a silhouette; on API 26-27 an EXIF-rotated photo can have its
  sides swapped; measured on DejaVu Bold, not the app's font, and on synthetic marks, not hand-painted ones.
