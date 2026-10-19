# Audit fix plan

An audit of the code against what [README.md](../README.md) says the app is for:
- the phone lives in a pocket between looks;
- the doodles are a persistent anchor that snaps the overlay back after tracking loss or screen-off, offline;
- painting progress makes the overlay stick tighter, not looser;
- there is one design image;
- the app works on any surface.

Every finding below gets fixed. Each one lists where it is, what is wrong, and the fix. Findings that more than one auditor reported are merged and listed once.

Status: `[ ]` open, `[x]` fixed (with the commit).

## A. Pocket, screen-off and snap-back

- [x] **A1. The relocalizer gets no frames while ARCore is lost.**
  - Where: `ArRenderer.kt:2949-2950` (`if (isTracking && frameCount % throttleRate == 0)` wraps `feedYuvFrame`).
  - Effect: snap-back waits for ARCore to recover on its own first. The lost-case sensor-turn pass (`MobileGS.cpp:915-936`) can never fire on ARCore.
  - Fix: feed frames whether or not ARCore is tracking (the native side's `relocWantsFrame` already bounds the rate), and use the native hunt cadence after a loss.
- [x] **A2. Frame feed is too slow after the anchor exists.**
  - Where: `ArRenderer.kt:2949` feeds every 10th render frame (about 3–6 Hz), below the native hunt pace (60 ms, `MobileGS.h:103`).
  - Fix: let `relocWantsFrame` pace it instead of a fixed divisor.
- [x] **A3. A dark pocket trips the "broken stereo" recovery.**
  - Where: `ArViewModel.kt:5044-5054` → `recoverFromBrokenStereo()` (`:5137`). The point count reads 0 once the anchor exists (`ArRenderer.kt:2647`, `:3066`, `:579-588`).
  - Effect: the session is reconfigured mid-mural and stereo depth is turned off for good.
  - Fix: never trigger it once `isAnchorEstablished`.
- [x] **A4. Weak, disagreeing fixes skip the agreement check after any loss.**
  - Where: `PoseFusion.kt:314` (`needsAgreement = !coldStart && …`). `coldStart` is set again on every non-tracking frame (`ArRenderer.kt:2086`).
  - Effect: a "false twin" on brick or tile moves the overlay.
  - Fix: exempt only the very first lock (no correction yet), not every relock after a loss.
- [x] **A5. A correct relock after a loss eases in instead of snapping.**
  - Where: `PoseFusion.kt:309, 327-337`. Below COLD_SNAP the blend is about 0.07 per relock. The ratio's denominator (`MobileGS.cpp:1222`) counts every pass plus the map and paint correspondences.
  - Fix: on a cold start, snap on a spread-gated bar, and compute the inlier ratio per correspondence source.
  - Done: the snap bar here; the ratio dilution by paint and map correspondences is removed at its source (B10 dedupe and prior gating, B7 map gating).
- [x] **A6. No touch lock in AR while pocketed.**
  - Where: `MainActivity.kt:741` keeps the screen on, but Freeze exists only in TRACE (`:2758-2770`).
  - Effect: fabric touches drag the overlay, switch modes (tearing AR down) or re-capture.
  - Fix: block input while the proximity sensor is covered, and offer Freeze in AR.
- [x] **A7. A pocket longer than 10 s raises a tracking-failure warning.**
  - Where: `ArViewModel.kt:4976-4979`, shown at `MainActivity.kt:2902`.
  - Fix: only while `!isAnchorEstablished`.
- [x] **A8. The badge says "locked" before any relock.**
  - Where: `MainActivity.kt:1656-1660` counts HOLDING as locked.
  - Fix: HOLDING after a loss reads as searching until a relock lands.
- [x] **A9. The overlay can jump when ARCore's own anchor resumes after a doodle relock.**
  - Where: `AnchorOrchestrator.kt:167-179`. The correction is stored relative to a frozen last-good base.
  - Fix: when the ARCore anchor resumes, re-base the correction so the composed pose is continuous.
- [x] **A10. A config-change `onDestroy` leaves `isAnchorEstablished` true with no session.**
  - Where: `MainActivity.kt:2218` → `destroyArSession()`.
  - Fix: reset the anchor state there as `exitArMode` does.
- [x] **A11. Pre-Phase-2 projects (no capture pose) can never snap back without a re-capture.**
  - Where: `ArRenderer.kt:1729, 2242-2246`.
  - Fix: derive a capture pose from the stored Doodle frame when it is missing.

## B. Native relocalizer and the Doodle

- [x] **B1. Self-grown rows are never saved.**
  - Where: `MobileGS.cpp:2162-2188` grows in memory only. `exportDoodle` is co-op only (`ArViewModel.kt:429, 462`); the save (`:4001-4011`, `:4303`) and the load (`:4519, 4541`) use the original capture.
  - Fix: persist the grown tail (rows and regions) with the project and restore it after the base. ARCore and standalone both.
  - Done: ARCore (`grownDoodle`) and standalone (`sphereSlamGrownDoodle`, C3).
- [x] **B2. Self-grow hits a hard cap with no eviction.**
  - Where: `kMaxWallMarks = 5000` is shared with the capture and close-ups (`MobileGS.h:248`; `MobileGS.cpp:2056, 2118, 2139-2148`).
  - Fix: evict rows that are no longer re-observed (painted-over originals first) instead of refusing growth. The 5000 cap is memory and stays.
- [x] **B3. The partition flips on suddenly after about 12 grown rows and drops every INSIDE original.**
  - Where: `MobileGS.cpp:734-736, 824, 2187`.
  - Fix: count only original backbone rows toward the threshold, and keep unpainted INSIDE originals in the match set.
- [x] **B4. Marks under the design are excluded from reloc even before any paint.**
  - Where: `MobileGS.cpp:824`; `DoodlePartition.kt:136-137`.
  - Fix: INSIDE marks stay in reloc until corroboration says paint covers them.
- [x] **B5. Artwork and wall descriptor types can drift apart.**
  - Where: `MobileGS.cpp:2921-2926`.
  - Effect: self-grow is refused (`:2138`, misleading `kGrowNoGeometry`), paint marks are skipped in reloc (`:1057`), and SuperPoint runs again every pass (`:1706-1711`).
  - Fix: re-register the artwork whenever the wall Doodle's descriptor type changes, and report a distinct code.
- [x] **B6. A SuperPoint Doodle with SuperPoint unavailable fails silently as `kRelocFewMatches`.**
  - Where: `MobileGS.cpp:805, 1102`.
  - Fix: a distinct reject code, surfaced to the UI.
- [x] **B7. The feature map is weakest when it is needed most.**
  - Where: `MobileGS.cpp:946, 1609-1636`. It needs only `mapPriorSeq > 0`, so it gates with a stale pre-pocket pose; growth adds foreground clutter.
  - Fix: require a recent prior and a minimum map confidence before using it; only grow points that land on the wall plane within tolerance.
- [x] **B8. The low-light enhancer is missing or one-sided.**
  - Where: standalone never sets the light level (`MobileGS.h:1098`). The ORB builds use CLAHE only, with no Zero-DCE (`MetricDoodleBuilder.kt:294-302`, `StandaloneFingerprintBuilder.detectOrb:242-252`), while live reloc enhances (`MobileGS.cpp:752`, `3034-3039`).
  - Fix: a luma-based light level in standalone, and ORB builds through the native enhancer.
- [x] **B9. Paint marks are matched on the plain pass only.**
  - Where: `MobileGS.cpp:1047-1072`. No scaled, rectified or sensor-turn passes.
  - Fix: include paint marks in those passes.
- [x] **B10. Paint-mark matching is unconstrained and double counts.**
  - Where: `MobileGS.cpp:1050-1072`, with no live-keypoint dedupe against wall correspondences. This lowers the inlier ratio (`PoseFusion.kt:294-296, 334`; `growTrusted` 0.6) and inflates the absolute inlier gates.
  - Fix: constrain to the predicted position when a prior exists, and dedupe on the live keypoint.
- [x] **B11. Build-time re-installs drop rows grown during the build, and any partition pushed meanwhile.**
  - Where: `MetricDoodleBuilder.kt:547-560`; `MobileGS.cpp:2313-2350`.
  - Fix: install the build result as a prefix and keep the grown tail and partition.
- [x] **B12. A depth-path Doodle may rectify with the wrong orientation.**
  - Where: `MobileGS.cpp:3230-3233` with `MainViewModel.kt:641-645`. Sensor-frame points against a display-oriented view.
  - Fix: verify the view convention and apply the same correction `MetricDoodleBuilder` uses.
- [x] **B13. The distortion head goes blind once only paint carries the lock.**
  - Where: `MobileGS.cpp:998` runs before paint marks are appended (`:1050`).
  - Fix: run it on all correspondences, paint included.

## C. Teleological progress

- [x] **C1. Re-capturing mid-mural destroys progress and treats the paint as bare wall.**
  - Where: `MetricDoodleBuilder.kt:111, 547` → `clearPaintMarks()` (`MobileGS.cpp:4095-4108`) wipes the marks and the design-relative grid. The new capture photo then becomes the bare-wall colour source (`MainViewModel.kt:874`; `MobileGS.cpp:3808-3824`). New-Doodle features on the paint are INSIDE and skipped (`:824`).
  - Fix: carry paint marks into the new frame, keep the grid, and never re-baseline bare-wall colour from a photo of an already-painted wall.
- [x] **C2. The grid's peer check vetoes large uniform fills (the first coat).**
  - Where: `MobileGS.cpp:3892-3909`. Unlatched design cells are peers, so a covering coat reads as lighting.
  - Fix: peers only from the ring, transparent cells and confirmed-bare cells.
- [x] **C3. Standalone starts from zero every session.**
  - Where: `ArViewModel.kt:3960-3996` saves no paint marks or grid. On reload the bare-wall colour comes from the first sighting (`MobileGS.cpp:3866-3871`), so painted cells are latched as bare.
  - Fix: persist paint marks and the grid for standalone, and never take a first-sighting baseline over cells with saved progress.
- [x] **C4. A baseline-margin failure resets confirmation counts.**
  - Where: `MobileGS.cpp:258-263`, then `:1873`, `:1895`. Repetitive designs never confirm.
  - Fix: a margin failure is "no data" and leaves counts alone.
- [x] **C5. Any layer edit re-registers the design and wipes corroboration.**
  - Where: `MainScreen.kt:662-670` is keyed on the whole `Layer`, so it runs `setArtworkDoodle` (`MobileGS.cpp:2996-3006`).
  - Fix: key on the layer's source and geometry only.
- [x] **C6. Any design nudge over 1 mm zeroes all confirmations.**
  - Where: `MobileGS.cpp:2451-2462`, pushed on every footprint change (`ArViewModel.kt:4225`).
  - Fix: re-project confirmations through the old→new placement and keep the ones that still land.
- [x] **C7. Low layer opacity freezes progress at 0%.**
  - Where: `MainScreen.kt:1183`. At ≤12.5% opacity every grid weight is 0 (`MobileGS.cpp:3330-3333`), and feature progress is suppressed (`:1953`, `:1968`).
  - Fix: build the guide composite at full opacity.
- [x] **C8. Filter passes can't find stylised paint.**
  - Where: `MobileGS.cpp:2856-2873`. Descriptors are sampled from the unfiltered design.
  - Fix: describe each filter's places on the filtered image as well, and keep both descriptor rows.
- [x] **C9. A fresh SphereSLAM hybrid page holds off the teleological correction.**
  - Where: `ArRenderer.kt:2256-2340`. The page is a photo of the original wall and degrades as it is painted.
  - Fix: let a paint-backed reloc correction through the arbiter when hybrid quality drops.
- [x] **C10. Per-feature confirmation counts are never saved.**
  - Fix: persist them with the paint grid.

## D. Modes, offline and design scope

- [x] **D1. Subject isolation downloads its model from Google Play services.**
  - Where: `feature/editor/build.gradle.kts:55` (unbundled `play-services-mlkit-subject-segmentation`), `SubjectIsolator.kt:22-38`. Offline it fails, blames the image, and caches the failure (`EditorViewModel.kt:679-682, 717-723`). The stencil tool uses it too (`:697`).
  - Fix: a segmentation model bundled in the APK. Until then, never cache a failure, and say the model is unavailable rather than blaming the image.
- [x] **D2. Link sends the capture's GPS location to the peer.**
  - Where: `ArViewModel.kt:465-468` → `ProjectManager.kt:1190-1197` zips `project.json`, including `captureEnvironment` lat/long (`GraffitiProject.kt:359`).
  - Fix: strip the location from the co-op snapshot.
- [ ] **D3. Overlay has no no-ARCore tracking fallback, despite the README.**
  - Where: `MainScreen.kt:783-792`, `MainActivity.kt:2642-2680`.
  - Fix: add the marked-shape planar tracking (the standalone OpenCV pipeline) to Overlay when ARCore is absent.
- [x] **D4. Freeze doesn't lock orientation.**
  - Where: `AndroidManifest.xml:82`; `requestedOrientation` is never set.
  - Fix: lock to the current orientation on Freeze and release it on unlock.
- [x] **D5. Controls at the top of the screen.**
  - Where: AR Recapture (`MainActivity.kt:1665-1680, 3991`); guest Leave (`:2127-2134`, `CoopSpectatorBanner.kt:33`); glasses Reconnect/Leave (`:2150-2166`); library header (`ProjectLibraryScreen.kt:120-160`).
  - Fix: move them to the bottom, on the rail's side.
- [x] **D6. Adjustment knobs ignore handedness.**
  - Where: `AdjustmentsPanel.kt:32` (`isRightHanded` is never read), `:113-200`.
  - Fix: anchor the knob rows to the thumb's side.
- [x] **D7. GPS runs at high accuracy for the whole AR session.**
  - Where: `LocationTracker.kt:53`; started at AR entry (`ArViewModel.kt:2668`).
  - Fix: one balanced-accuracy fix at capture time.
- [ ] **D8. UI text describes several layers.**
  - Where: `strings.xml` `nav_design_info`, `nav_blending_info`, `help_mockup`, `help_add_img`, `help_export`, `onboarding_design`, `guidance_open_via_design_*`, `nav_open_info`, `nav_export_info`. Also `layerCount` (`ArViewModel.kt:470`) and README "Multi-layer image manipulation".
  - Fix: reword everywhere to the one design, in all 15 locales; remove dead strings and `layerCount`.
- [ ] **D9. UI text frames the app as murals only.**
  - Where: `lib_no_projects_hint`, `help_project_host`, `help_new`, `help_save`, `help_load`, `onboarding_mockup[0]` ("brush"), dead `settings_mural_method_label` and `nav_update_info`.
  - Fix: say piece/project and spray can, in all 15 locales.
- [x] **D10. Guest edits can land during Freeze.**
  - Where: `EditorViewModel.kt:1523-1547` applies guest ops with no mode or touch-lock check.
  - Fix: hold guest ops while frozen and apply them on unlock.
- [x] **D11. Dialogs can take touches during Freeze.**
  - Where: a shared-in image raises the replace dialog (`MainActivity.kt:349-356, 625-629`).
  - Fix: defer incoming shares until unlock.
- [x] **D12. The privacy policy doesn't disclose ARCore (Google Play Services for AR).**
  - Fix: add the disclosure to `PRIVACY_POLICY.md` in every locale.

## E. Documentation and defaults

- [x] **E1. README and KDocs say drift correction and self-grow are off by default under Settings.** In the code they default ON and live in the AR diagnostics panel (`SettingsRepositoryImpl.kt:166, 176, 209`; `ArViewModel.kt:983-991, 1027-1057, 1040, 1050, 1062, 4884`).
  - Fix: make the docs match the code.

## F. Persistence and Link

The self-grown rows not being saved is B1, and standalone paint marks and grid not being saved is C3. Both appear here too.

- [x] **F1. The save on app exit loses the race with engine teardown.**
  - Where: `MainActivity.kt:2218-2219` starts `destroyArSession()` asynchronously, then calls `slamManager.destroy()`. The save (`ArViewModel.kt:3825-3827`, after up to 1.5 s in `detachSessionBounded`) finds a null engine (`GraffitiJNI.cpp:321`) and returns silently (`:4004`).
  - Fix: wait for the teardown save to finish before destroying native.
- [x] **F2. Screen-off and backgrounding don't save.**
  - Where: the pause path `ArViewModel.kt:2832-2833` → `pauseArSessionInternal` (`:3770`). Autosave runs every 30 s, past thresholds only.
  - Fix: save on `ON_STOP`.
- [x] **F3. Switching projects, or joining Link, drops the outgoing project's unsaved native state.**
  - Where: `ArViewModel.kt:2001-2013`; `alignToPeer` (`:586`).
  - Fix: run `saveMapBlocking` for the current project before the switch or `alignToPeer`.
- [x] **F4. The angled-view rectification compares camera poses from two sessions on a return visit.**
  - Where: `ArViewModel.kt:4517-4525` restores the capture view; `computeRectifyHomography` (`MobileGS.cpp:1330-1380`) assumes a shared VIO world.
  - Fix: rectify only once a lock in the current session gives the capture view in the current world.
- [x] **F5. A depth-path capture can never re-anchor on reopen, yet says "Doodle Saved & Locked".**
  - Where: `MainViewModel.kt:585-595, 664, 690-693, 711`; reload at `ArViewModel.kt:4539-4548`; `ArRenderer.kt:1728-1729, 2242-2247`; hosting refused at `CoopSpatialFrameFactory.kt:58-63`.
  - Fix: record `captureAnchorCam` (and intrinsics and view) from the anchor on the depth path.
- [x] **F6. A Link guest gets none of the host's paint-derived references.**
  - Where: host sends Doodle rows only (`ArViewModel.kt:462`); `alignToDoodle` clears the guest's paint marks and map (`MobileGS.cpp:2585-2587, 2622-2628`); the archive's `paintMarks` are never installed (`ArViewModel.kt:116-123, 2222-2230`).
  - Fix: after `alignToPeer`, install the host's paint marks (they are in the Doodle frame), and send the grown rows (B1).
- [x] **F7. Entering AR as a guest skips the guest guard.**
  - Where: `ArViewModel.kt:2653-2654` → `loadDoodleIfExists()` bypasses `shouldRestoreLocalArCoreDoodle`, restoring the host's copy with host intrinsics, anchor, view, map and photos (`:4519-4530`).
  - Fix: apply the guard in `setArMode`, and re-run `alignToPeer` for guests.
- [x] **F8. The guest keeps its previous wall's normal.**
  - Where: `alignToDoodle` doesn't reset `mHasWallNormalEnu` (`MobileGS.cpp:2581-2633`), so `wallOrientationRejects` (`ArRenderer.kt:3576-3603`) rejects the host wall.
  - Fix: clear it at join, or set it from the host project.
- [x] **F9. The wrong capture photo can be used for the grid.**
  - Where: `ArViewModel.kt:4602-4608` uses `doodleImageUris.lastOrNull()` with the ARCore intrinsics, but standalone captures append to the same list (`MainViewModel.kt:346, 387`) and a rejected candidate's image stays (`:498-536`).
  - Fix: keep a dedicated ARCore capture-photo reference, and drop rejected candidates' images.
- [x] **F10. A standalone capture nulls the ARCore paint marks and grid.**
  - Where: `MainViewModel.kt:385-386`.
  - Fix: keep them (they belong to the ARCore frame).
- [x] **F11. Silent and lossy failures.**
  - Wall-map and paint save errors only go to logs (`ArViewModel.kt:3901-3908, 3922-3926`).
  - The partition write at `:4303` has no try/catch in a `viewModelScope` launch, so a write error crashes the app.
  - Any decode or `require` failure drops the whole project from the library (`ProjectManager.kt:731-736`).
  - Projects of 256–512 MiB export but can't be hosted (`Limits.kt` vs `ProjectManager`).
  - Fix: surface save failures to the user; catch the partition write; keep an undecodable project listed as damaged with its files intact; align the size limits.
  - Done. Size limits: Link holds a bulk in memory on both ends, so its 256 MiB cap stays. Hosting a larger project now names both sizes and points to export (512 MiB, streamed).

## Order of work

Fixes land in commits that group related findings:
1. A1, A2, A3, A4, A5, A7, A8, A10.
2. B1, F1, F2, F3, F6.
3. B2, B3, B4, B5, B6, B9, B10, B11, B13.
4. C1 to C10, F10.
5. F4, F5, F7, F8, F9, F11, A9, A11, B7, B8, B12.
6. A6, D1 to D12, E1.

Each commit carries its tests and docs and ticks its boxes here.
