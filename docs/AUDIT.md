# GraffitiXR codebase audit — squash list

Full read-only audit across all modules (app, core:*, feature:*, collab, native C++). No `TODO`/`FIXME`
markers exist — every item below is a *silent* stub, unwired path, or bug found by reading the logic.
`[ ]` = open, `[x]` = fixed. Grouped by severity; "Decisions needed" holds the design-dependent ones.

## HIGH — crash / security / data-loss / ANR / OOM

- [x] **AR overlay teleports to world origin on tracking loss.** `feature/ar/anchor/AnchorOrchestrator.kt:72` — `getConsensusMatrix()` returned IDENTITY when no anchor is TRACKING → jumps to origin on any brief tracking loss. ✅ holds the last-good matrix; also synchronized all shared state (GL vs main thread) per PR review.
- [x] **Main-thread native map-save → ANR.** `app/MainActivity.kt:1191`. ✅ `withContext(Dispatchers.IO)`.
- [ ] **Save-race: wall-feature-map vs editor save (data-loss).** `ArViewModel.saveWallFeatureMap():1231` fire-and-forgets `projectManager.saveProject(currentProject.copy(wallFeatureMap=map))` while `EditorViewModel.saveProject():550` concurrently overwrites `currentProject` (full-object) from a stale snapshot → last write wins, the fresh wall map (or the layer edits) is silently lost. **Architecturally significant** — the correct fix makes `saveMapBlocking` synchronously merge the wall map into the repository's `currentProject` *before* the editor save reads it (and the editor save read-modify-write). Flagged for confirmation (surfaced by PR #1755 review; not a regression — pre-existing).
- [x] **Zip-bomb OOM (import + spectator).** `core/data/ProjectManager.kt:318,468` — `MAX_IMPORT_BYTES` checked *after* `readBytes()` already buffered the whole entry. ✅ bounded `readEntryBounded()` streaming, aborts mid-stream.
- [x] **Zip-bomb OOM (.azp install).** `core/data/azphalt/AzpInstaller.kt:51` — reads every entry fully into memory, no size cap, from an attacker-controlled URL. ✅ streams with a 64 MB cumulative cap (`MAX_PACKAGE_BYTES`).
- [x] **Fast-stroke data-loss.** `feature/editor/EditorViewModel.kt:1550` — when `strokeWorkingBitmap==null`, the non-Liquify branch commits the *unmodified* bitmap; the stroke shows in history but is never drawn/saved → vanishes on screen and on reload. ✅ the fallback now rasterizes the collected points onto a fresh bitmap copy (mirrors the catch-up path) before committing.
- [x] **Concurrent-mutation crash mid-stroke.** `feature/editor/EditorViewModel.kt:1433` — `strokeCollectedPoints` (ArrayList) is `.add()`-ed on main while a Default coroutine reads `.toList()` → CME/AIOOBE crash. ✅ all access routes through `synchronized(strokeCollectedPointsLock)` helpers (`resetStrokePoints`/`addStrokePoint`/`snapshotStrokePoints`).
- [x] **JNI fingerprint-restore OOB read.** `core/nativebridge/cpp/GraffitiJNI.cpp:807,824` — `nativeRestoreWallFingerprint(Metric)` builds `cv::Mat` from a byte array with no length check → native OOB on malformed `.gxr` (the sibling FeatureMap path at :858 IS guarded). ✅ both paths now reject when `GetArrayLength(descArray) < rows*cols*CV_ELEM_SIZE(type)`, guard null refs + negative dims, use the safe `i+2 < ptsLen` points loop, and pass anchor/intr only when correctly sized (16/4).
- [x] **Untrusted co-op parser overflow crash.** `core/nativebridge/cpp/MobileGS.cpp:886` — `alignToFingerprint` parses peer bytes; `numPoints*sizeof(Point3f)` overflows on 32-bit and `cv::Mat` is built from peer dims *before* the bounds check → native crash. ✅ all length math is 64-bit, the descriptor header is sanity-checked (valid depth/channels, dims ≤ 100000) and bounds-validated against the buffer *before* `cv::Mat` is allocated.
- [ ] **Co-op silently drops all guest edits** (see Decisions — architectural). `collab/OpEmitterImpl.kt:12` + `GuestSession`.
- [ ] **Co-op session torn down by a normal large edit.** `collab/session/HostSession.kt:147` + `DeltaBuffer.kt:26` — a single delta >5 MB → `close(NetworkLost)`; `Op.LayerBitmapReplace` carries a full-canvas PNG. Fix: chunk/stream large ops (and don't count ops toward the fatal cap while waiting for a guest — `HostSession.kt:139`).

## MED — incorrect / incomplete behavior

- [ ] `feature/editor/util/ImageProcessor.kt:162` — BLUR tool never sets Paint color → paints opaque BLACK instead of blurring. Fix: sample+blur the underlying region.
- [ ] `core/design/rendering/ProjectedImageRenderer.kt:49` — `applyNativeTransform`/`applyNativeBlendMode` are empty bodies (comments claim JNI) → `updateLayerTransformation`/`setLayerBlendMode` are silent no-ops. Fix: implement or delete the renderer (it's also never instantiated).
- [x] `feature/editor/EditorViewModel.kt:797` — `setSegmentationInfluence` launches an uncancelled Default coroutine per slider tick; stencil-preview reruns full K-means each tick → pile-up. ✅ added `segmentationInfluenceJob`, cancelled before each recompute.
- [x] `feature/editor/stencil/StencilPrintEngine.kt:178` — last col/row `srcRect` extends past the bitmap → OOB sampling → garbage edge tiles in the printed PDF. ✅ clamps `srcRect` to the source bounds and shrinks `dstRect` by the same fraction (early-return + recycle for a fully off-edge tile).
- [ ] `feature/ar/rendering/ArRenderer.kt:1133` — per-frame coroutine calls `frame.acquireDepthImage16Bits()` off the GL thread, racing `session.update()` (latent; depth default off). Fix: read depth on the GL thread.
- [x] `feature/ar/ArViewModel.kt:674` — `setImperialUnits()` never persists via `settingsRepository` (unlike sibling setters). ✅ now persists via `settingsRepository.setImperialUnits(...)` (keeps the optimistic state update).
- [x] `core/common/model/Fingerprint.kt:11` — no `init{}` `require()` that `descriptorsData.size == rows*cols*elemSize` (unlike `WallFeatureMap`) → corrupt Fingerprint feeds the unguarded native restore. ✅ added an `init{}` mirroring `WallFeatureMap`: non-negative dims, `points3d` a multiple of 3, and the descriptor blob divides evenly into `rows` (and its row stride into `cols`).
- [x] `core/common/azphalt/AzpSignature.kt:74` — a present-but-malformed `signature.json` parses to null → `evaluate()` returns UNSIGNED instead of INVALID. ✅ `evaluate()` now returns INVALID when `signatureJson` is non-blank but won't parse; UNSIGNED only when truly absent/blank.
- [x] `core/data/azphalt/ExtensionRepository.kt:97` — `openSource()` allows cleartext http, no timeouts, and `install()` fetches network under `synchronized(lock)`. ✅ https-only (cleartext http rejected) + connect/read timeouts; `install()` now downloads to a bounded temp file *outside* the lock and holds the lock only for the unpack + rescan.
- [x] `core/data/azphalt/ExtensionRepository.kt:40` — `_installed` StateFlow init runs `scanInstalled()` (disk IO + parse + sig eval) in the `@Singleton` ctor on the injecting thread. ✅ inits empty and populates on an injected `DispatcherProvider.io` scope.
- [x] `core/data/azphalt/AzpInstaller.kt:104` — an IOException during unpack leaves a partial `<id>/` dir (contradicts the "no partial install" contract). ✅ unpacks to a dot-prefixed staging dir and atomically `renameTo` the final dir; any failure deletes staging (rescan skips dot-dirs).
- [ ] `core/nativebridge/cpp/GraffitiJNI.cpp:498` — `nativeFeedYuvFrame` I420 branch copies U/V into mis-sized ROIs → uninitialized chroma → wrong colors. Fix: interleave V,U as the YuvConverter path does.
- [ ] `core/nativebridge/cpp/GraffitiJNI.cpp:222` — `bitmapToMat`/`matToBitmap` ignore `AndroidBitmap_*` return codes (null pixels → SIGSEGV) and assume `stride==width*4`. Fix: check results, honor `info.stride`.
- [ ] `app/MainActivity.kt:1332` — Marketplace "Apply" closes the panel then `applyInstalledLut` silently returns on no-layer/parse-fail → button "does nothing". Fix: surface a toast / keep panel open on failure.
- [ ] `feature/dashboard/SettingsScreen.kt:126` — camera/storage permission captured in keyless `remember{}`, never rechecked on resume → stale after returning from App Settings. Fix: recompute on ON_RESUME.
- [x] `feature/editor/CurvesUtil.kt:11` — natural cubic spline documented as "monotone" (can overshoot). ✅ replaced with a true Fritsch–Carlson monotone cubic Hermite (tangent clamping), so the tone curve can't overshoot/invert; the doc is now accurate.

## LOW — dead code / unwired / minor

- [ ] `feature/ar/rendering/` — `FeaturePointRenderer` (empty stub), `GridRenderer`, `MiniMapRenderer`, `SimpleQuadRenderer`, `AugmentedImageRenderer` all never instantiated. Delete (or wire).
- [ ] `feature/ar/util/MeshGenerator.kt:35` — never called + wrong depth mask (`0xFFFF` vs `0x1FFF`) + ignores rowStride. Delete or fix.
- [ ] `feature/ar/sensor/PhoneCameraAdapter.kt`, `PhoneImuAdapter.kt` — `@Inject` classes never wired. Delete or wire.
- [ ] `feature/ar/DisplayRotationHelper.kt:76` `getViewportAspectRatio()`; `feature/ar/rendering/ArRenderer.kt:659` retired showVoxels/showMesh toggles.
- [ ] `feature/editor/` dead: `EditorViewModelExt.applyStrokeToActiveLayer`, `BackgroundRemover` (dup of ML-Kit `SubjectIsolator`), orphan dialogs (ColorBalance/AdjustmentSlider/SizePicker/DoubleTapHint), `MultiGestureDetector`/`RotationGestureDetector`, `StencilProcessor.processSingle`, `StencilPrintEngine.saveLayerPngs`, empty `onGeneratePoster`/`onDoubleTapHintDismissed`/`onOnboardingComplete`. Delete.
- [ ] `feature/editor/util/ImageProcessor.kt:102` — `ux = rx/layerScale` no zero-guard → possible Infinity coords. Fix: clamp scale.
- [ ] `feature/dashboard/` dead: `SettingsViewModel.completedTutorials/markTutorialComplete` (dup), `MarketplaceViewModel.isInstalled/clearStatus`, `OnboardingScreen.kt` (scaffold), `DashboardUiState` unused fields. Delete; call `clearStatus()` on panel open/close.
- [ ] `feature/dashboard/DashboardViewModel.kt:167` — `fetchLatestRelease()` leaks `HttpURLConnection` off the 200 path. Fix: `finally { disconnect() }`.
- [ ] `app/RailIdEnumerator.kt:72` — omits `edges`/`magic` suffixes → integrity check can't catch dup. `app/MainActivity.kt:185` — permission launcher ignores Nearby/Location result. `app/ui/coop/CoopHostQrOverlay.kt:44` — per-pixel QR bitmap on main thread.
- [ ] `core/data/azphalt/ExtensionRepository.kt:117` install epoch derived from file mtime; `:83` dead `installedLuts()`; `ProjectManager.getMeshPath()` dead; data layer hardcodes `Dispatchers.IO` despite DI provider.
- [ ] `core/common/util/YuvToRgbConverter.kt` dead class. `core/nativebridge` MatSerializer untrusted `type`.

## Decisions needed (implement vs remove — design intent)

- [ ] **Co-op: collaborative or spectator?** Guests can receive but never transmit edits (`OpEmitterImpl`/`GuestSession` have no upload path). Is bidirectional sync in-scope (build a guest→host DELTA channel) or is host-broadcast/spectator by design (make it explicit, stop silently dropping)?
- [ ] **AR-glasses calibration: finish or remove?** `glassesWorldHitForTimestamp` is a stub (identical src/dst), the solve degenerates on coplanar taps, and `applyPhoneToGlasses` is never consumed — the whole Meta/Xreal path is non-functional and has no UI entry point.
- [ ] **Vestigial native layer.** Post voxel/splat deletion, ~20 native fns are no-ops / `0`/`null`/`false` (`saveModel`/`loadModel`/`getSplatCount`/`pushFrame`/`getAnchorCandidates`/draw*) while AR/editor VMs still call them and gate autosave/reload/persistence/anchor-promotion on the results (and depth/stereo JNI still burn per-frame CPU). Prune the dead API + callers, or restore the features?
- [ ] **Unwired features:** Curves/tone-curve tool (no UI entry, LUT never applied), two-view triangulation fingerprint path, `GuideGenerator` grid/4-X modes, the AMBIENT "ink-develop" scan-reveal shader (gutted to passthrough), live-registry marketplace client (`catalogFromRegistry` never wired). Wire up or delete each?
