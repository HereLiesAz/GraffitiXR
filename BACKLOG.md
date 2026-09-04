# Backlog

## Build verification

This session's sandbox previously had no Android SDK, so every fix above the Freeze-cleanup
commit was verified by manual code review and grep, not a build — a real gap: it's how the
`addImg` field got mistakenly deleted and broke `main` (caught by CI, not by me). Fixed by
provisioning the SDK/NDK locally (`platforms;android-37.0`, `build-tools;35.0.0`, `cmake;3.22.1`,
`ndk;27.2.12479018` under `/opt/android-sdk`, `local.properties` pointed at it) rather than
continuing to defer verification to some future session. `compileDebugKotlin`,
`testDebugUnitTest` (705 tests, 0 failures) and `detekt` all now run for real here. Fixed two
`SwallowedException` findings detekt caught in test-only catch blocks (`WallFeatureMapTest.kt`,
`CaptureEnvironmentTest.kt`) — unnamed the caught exceptions per the project's `_` convention.

## Security alerts

### Done

- **CodeQL #3/#4/#5 — Inclusion of functionality from untrusted source** (`docs/index.html`). Added Subresource Integrity to the three cdnjs `<script>` tags (rellax 1.12.0, gsap 3.12.2, ScrollTrigger 3.12.2): `integrity="sha512-…"` + `crossorigin="anonymous"` (Action A). **Correction:** the rellax hash was wrong — the SRI mismatch silently blocked `rellax.min.js`, breaking the parallax effects. Recomputed the sha512 from the file the CDN actually serves and fixed it; gsap/ScrollTrigger hashes verified as already correct. The tailwind play-CDN (line 7) intentionally has no stable SRI and is not one of the flagged alerts, so it is left as-is.
- **CodeQL #6 — Multiplication result converted to larger type** (`core/nativebridge/src/main/cpp/SurfaceUnroller.cpp:100`). `mCount * mCount` was computed as `int` and then widened to `size_t` for the `std::vector` ctor; overflows for `mCount ≥ 46341`. Fixed by casting both operands to `std::size_t` before multiplying.
- **Dependabot #27 — Netty HTTP header injection in HttpProxyHandler** (`io.netty:netty-handler-proxy`). Fixed by forcing the netty family (incl. `netty-handler-proxy`) to `4.2.15.Final` in `build.gradle.kts` `commonForcedDependencies`. **Verified:** 4.2.15.Final addresses CVE-2025-67735 and subsequent regressions.

- **Dependabot #23/#24/#25 — Bouncy Castle (covert timing channel / LDAP injection / risky crypto algo).** Fixed by forcing `org.bouncycastle:{bcprov,bcpkix,bcutil}-jdk18on:1.84` in `build.gradle.kts` `commonForcedDependencies` (1.84 first patches all three; the three artifact versions must match).

- **Task 17 — Co-op Implementation.** Real project serialization and spectator load implemented in `ProjectManager`. Wired `StrokeComplete` Op in `EditorViewModel` using bitmap-space mapping. Verified host-guest drawing sync logic.
- **Co-op — Transport security & robustness.** Encrypted the peer-to-peer transport (protocol v2: token-derived AES-256-GCM per frame, nonce/proof handshake, no token on the wire) and hardened the sessions: accept-loop survives bad handshakes, ops are lossless across reconnects (seq/encode/buffer at enqueue), 15s socket read timeouts, guest re-syncs on host `sessionId` change, bounded pre-handler spectator-op buffering. Import/spectator load hardened against Zip-Slip. See `collab/` and `core/data/ProjectManager.kt`.
- **Relocalization — Fingerprint JNI ABI.** Fixed `nativeSetWallFingerprint` silently returning null (native looked up a stale `Fingerprint` constructor descriptor after a field was added). Native now builds through a frozen `Fingerprint.fromNative` factory guarded by `FingerprintJniContractTest`.
- **Task 15 — QR Scanner.** Integrated ZXing-based QR scanner into `MainActivity`. Search button now triggers live scanner to join sessions.
- **Voxel Memory — Frustum Culling.** Implemented true NDC-based visible splat confidence calculation in `VoxelHash`.
- **Relocalization — Thread Safety.** Added mutex locking to `mWallDescriptors` and `mWallKeypoints3D` in `MobileGS` to prevent races between JNI updates and the background PnP thread.

#### Glee audit pass (2026-09-04)

Five parallel adversarial audits (code-vs-claims, UX/interaction, conceptual/product design,
architecture/engineering, docs cross-consistency). Full findings are in that session's transcript;
fixed this pass:

- **`EditorViewModel` had a second, dumber project-load path racing `ArViewModel`'s.**
  `restoreWorld`/`internal val slamManager` skipped fingerprint-region partitioning, the
  legacy-frame refusal, and clearing stale design placement — silently corrupting reloc state on a
  project switch. Deleted the whole path; `ArViewModel.loadFingerprintIfExists` is now the only
  loader. Also dropped `:feature:editor`'s `:core:nativebridge` dependency, which existed only for
  this.
- **"Open" replaced the design outright with no confirmation**, contradicted by four
  strings/comments calling it "add a layer." Added a stage-then-confirm step
  (`EditorUiState.pendingReplaceUri`, `EditorActions.confirmReplaceDesign`/`cancelReplaceDesign`) —
  a picker result only replaces immediately when there's nothing to lose; otherwise it asks first.
  Undo already covered this (`pushHistory()` runs before the replace), so the dialog says so.
- **`docs/TELEOLOGICAL_SLAM.md` and `docs/DISTORTION_HEAD.md` described the wrong mechanism as the
  one producing painting-progress/confidence.** The bundled `distortion_head.onnx` (loaded
  unconditionally every AR session) is the actual producer in a normal install; the
  descriptor-similarity test they described in detail is an inert fallback for a build with the
  asset stripped. Corrected both docs and the stale `confGlobal` KDoc in `PoseFusion.kt` (which
  still described the pre-Phase-5b whole-design-progress semantics `CONF_FLOOR`'s own doc, five
  lines above it, had already retracted).
- **`nativeGetAnchorTransform` was typed non-null in Kotlin but the JNI can return null** (an
  allocation failure on the 16-float result array, `GraffitiJNI.cpp`). `SlamManager.getAnchorTransform`
  is now `FloatArray?`; all three call sites (`ArViewModel`'s doodle-capture precondition check,
  and `ArRenderer`'s backbone-fallback and eval-truth-pose reads) handle the null case instead of
  risking an NPE on the GL thread. This also makes `awaitAnchorTransform`'s existing `m == null`
  guard live — it was dead code under the old non-null signature.
- **`:core:nativebridge`'s ProGuard rules never reached R8** — no `consumerProguardFiles`, so
  release builds survived only because `app/proguard-rules.pro` duplicated the JNI keep rules by
  hand. Added `consumerProguardFiles("consumer-rules.pro")` and moved the real rules there.
  `:core:common` had the mirror-image bug: its `build.gradle.kts` referenced a `proguard-rules.pro`
  that doesn't exist (removed the dangling reference) while its actual `consumer-rules.pro` — the
  file that *is* wired — carried zero real rules despite owning `Fingerprint`, which is constructed
  from native via a frozen JNI factory. Added the `Fingerprint` keep rules there so they travel with
  the class that needs them, rather than living only in the app's copy.
- **Removed confirmed-zero-caller dead code found during this pass:** the `mobilegs::exportFingerprint`
  /`alignToFingerprint` free-function wrappers in `MobileGS.cpp` (unlocked reads of `gSlamEngine` —
  the real, correctly-locked callers are `MobileGS::exportFingerprint`/`alignToFingerprint`, called
  directly under `gEngineMutex` from `GraffitiJNI.cpp`; the wrapper namespace had no callers and no
  header declaration anywhere); the eight inert `core/nativebridge/libs/litert_npu_runtime_libraries/`
  Gradle modules (never `include()`d in `settings.gradle.kts`, one of them declaring a
  `:core:nativebridge`→`:app` dependency inversion that would have mattered had it ever been wired
  in) and the now-unused `android-dynamic-feature` plugin alias they were the only reference to; and
  28 unused `AppStrings.Help` fields (`targetHost` through `cloudOffset` — an old per-layer
  authoring toolset with zero call sites) plus their string resources across all 15 locale
  `strings.xml` files and the orphaned `ic_ps_liquify.xml` drawable.
- **Two UX-audit help-text findings, fixed:** `HelpItemsBuilder`'s `"item.help"` entry mapped to
  `strings.nav.help` (the rail label "Help") instead of `strings.nav.helpInfo` (the actual guidance,
  "Tap any button while Help is active to see what it does") — Help's own help entry just repeated
  its own name. Fixed to use `helpInfo`. Separately, Trace mode's `help_lock_trace` string described
  Freeze (which locks the *entire touchscreen*, escapable only via a Volume Up/Down/Up/Down
  sequence) without ever mentioning that escape sequence — a stranded user reading the one help
  entry that should tell them how out had no way to find out. Added it, in all 15 locales (English
  first, then translated the remaining 14 — de/es/fr/hu/it/ja/nl/no/pt/ru/sv/tl/zh-rCN/zh-rHK —
  reusing each locale's existing `unlock_hint` string's terminology for the volume sequence).

#### Dead-features clearance pass

The audit's "Dead / unreachable features" section is closed. Actions:

- **Deleted** (~700 LOC): `CrashHandler` + `CrashActivity` (unregistered), the empty placeholder `MockupScreen`/`OverlayScreen`/`TraceScreen` (real rendering is in `MainScreen`), `MaskingScreen` + `ArViewModel.updateMaskPath` + `UiState.maskPath` (unrouted), `ArViewModel.setPlaneConfirmationBorder` + the renderer flag (dead), `ArViewModel.applyEraseToMask` (redundant with `TargetCreationFlow.eraseColorBlob`), `CollaborationManager.stopHosting()` (`leaveSession` is a strict superset), the `Tool.LIQUIFY` branch + `applyLiquifyNative` stub in `ImageProcessor` (unreachable — `DrawingEngine.composite` routes LIQUIFY through `SlamManager.applyLiquify`), Kotlin `WarpableImage`/`VirtualCamera`/`SurfaceUnroller` research code, Vulkan `splat.{vert,frag}[.spv]` assets (Vulkan backend removed), and the four unused transform-lock companion helpers in `EditorViewModel`.
- **Deleted enum:** `EditorMode.STENCIL` — auto-bounced to MOCKUP and had no route; per-layer stencil generation (`onGenerateStencil`) is untouched.
- **Wired up:** AR tap-to-distance UI now gates on `(isDualLensActive || currentCenterDepth > 0f)` instead of the deliberately-disabled ARCore Depth API — the reticle + distance chips light up on capable devices. Per-mode transform-lock toggle: a **Lock** rail sub-item under each non-Design mode folder (AR/OVERLAY/MOCKUP/TRACE), turning cyan when engaged — the reducer already respected `isTransformLocked`.
- **Fixed:** `LocalIp.discover()` now picks the source address of the default route via the UDP-connect trick, so QR pairing advertises a LAN-reachable IP on multi-interface (cellular + Wi-Fi, VPN) devices.

Still open, not touched this pass:

- **Glasses AR session** — ~640 LOC of overlays + calibration exists, but `glassesWorldHitForTimestamp` hit-tests the same phone screen point for src/dst so Procrustes always returns identity. A real fix needs a glasses-side world lookup — substantial new native/SDK integration. Left as WIP.
- ~~AR freeze-preview~~ — **resolved, was mistaken identity.** `onFreezeRequested`/`FreezePreviewScreen`/`unfreezeRequested` was never a "hold the design still while painting" feature — it was an unused diagnostic screen (ORB feature blobs + a depth-warning banner) for reviewing target-capture quality, with no caller anywhere. The real Freeze is Trace mode's touchscreen lock (`isTransformLocked`, escapable via Volume Up/Down/Up/Down — see `help_lock_trace` above), already shipped. Deleted the diagnostic chain: `FreezePreviewScreen.kt`, `ArViewModel.onFreezeRequested`/`onFreezeDismissed`/`onUnfreezeRequested`/`unfreezeRequested`, `UiState.freezePreviewBitmap`/`freezeDepthWarning`, and the `MainScreen`/`MainActivity` wiring.

#### Export & YUV clearance pass

- **Export composition is now mode-aware.** The Export rail item dispatches per mode: AR reads the composited GL framebuffer via `glReadPixels` (camera + wall-anchored overlay, matches what the user sees minus the Compose UI); Overlay uses CameraX `ImageCapture.takePicture` for the sensor still and composites layers on top at screen positions; Mockup unchanged; Trace exports a transparent-background PNG (was a solid `canvasBackground` fill). See `EditorViewModel.exportImage(backgroundBitmap, skipLayerComposite)`, `ArRenderer.onDrawFrame`'s `exportRequested` block, and `MainActivity.kt`'s `onExportRequested` handler.
- **Real JNI YUV→RGBA converter.** `ImageProcessingUtils.convertYuvToRgbaDirect` (the fake "zero-copy" path that JPEG-round-tripped every capture) is deleted. Replaced by `nativebridge.YuvConverter` — OpenCV `cvtColor(COLOR_YUV2RGBA_NV21)` on ARM NEON, written directly into a caller-owned `Bitmap`. Now only used by AR target capture (the export site went to `glReadPixels`). Contract test locks the JNI descriptor.
- **`ArViewModel.requestExport` is finally wired.** The rail's Export in AR mode calls it; it stashes the callback on `renderer.onExportCaptured` and flips `exportRequested = true` (already-correct implementation was just unreachable).

#### Full-codebase audit pass

`docs/AUDIT.md` is the itemised record; the headline items:

- **AR map autosave had never run.** `saveMapNow()` and the 30 s autosave loop both keyed on
  `slamManager.getSplatCount()`, a hardcoded `0` since the voxel/splat map was deleted, so the
  accumulated point cloud and the wall feature map were persisted only on AR exit and app
  background. Both now key on the live accumulated ARCore cloud.
- **Co-op survives a large edit.** The delta replay buffer refused an oversized op and treated the
  refusal as fatal, so one Liquify warp — or simply editing while waiting for a guest to join —
  ended the session. It now coalesces per-layer bitmap replaces, evicts on overflow, and falls back
  to a bulk re-sync when a reconnect lands in the gap.
- **Guest edits are no longer dropped in silence.** Co-op is host-broadcast; a guest's edit reached
  nobody with neither end told. It now says so once per session.
- **The azphalt marketplace is reachable.** The whole extension/LUT stack had no entry point from
  the app; it is now **Project ▸ Extensions**, and applying an installed LUT grades the active layer.
- **The layer list has an opener, and layers can be removed.** Add-a-layer was one-way: nothing could
  open the panel, and remove/rename/reorder/hide were implemented, tested and unreachable.
- **The wall feature map can be switched on.** `setMapRelocEnabled`/`setMapBuildEnabled` had no
  caller, so phases 2b/3 never ran and the `.gxr` map was always empty. Now a persisted switch beside
  drift-correction and self-grow.
- **The vestigial native voxel/splat layer is gone** (~20 no-op functions and their callers, the idle
  map thread, the per-frame depth decode and stereo block-match that fed a no-op, the software-stereo
  path that advertised depth it could not produce, and the Liquify/ImageWarper subsystem).
- **`./gradlew detekt` works.** Its config file had never been committed, so the command
  `docs/contributing.md` asks contributors to run failed for every module. The tree is now clean
  under it.

Verified by `testDebugUnitTest` (413 tests), `externalNativeBuildDebug`, `detekt` and
`assembleDebug`. Nothing was device-verified — see the note at the top of `docs/AUDIT.md`.

### Todo

- _No open security alerts._ (CodeQL #3/#4/#5 SRI and the Bouncy Castle advisories #23/#24/#25 are resolved — see the Done section above.)

Remaining open items (all in `docs/AUDIT.md` under "Still open"): Glasses AR session,
bidirectional co-op, and a short list of unreferenced diagnostic/eval knobs.

#### Glee audit pass (2026-09-04) — not yet acted on

Correctness bugs, worst first:

- ~~Relocalization status chip can never render~~ — **fixed.** `MainActivity.kt`'s gate condition
  (`!arUiState.isAnchorEstablished`) and `RelocStatusBadge`'s own early-return
  (`if (relocState == RelocState.IDLE) return`) were mutually exclusive, so `SEARCHING`/`TRACKING`
  were dead branches. `RelocState.IDLE` means "target not yet confirmed" (`UiState.kt`) —
  SEARCHING/TRACKING both require an established anchor — so the call site's gate is now
  `arUiState.isAnchorEstablished` (was negated).
- ~~First-run AR instruction tells the user to tap the icon that hides the rail~~ — **fixed**, in
  both places it appeared (`showDesignInstructionsDialog` and `PostTargetInstructionOverlay`).
  `noMenu=true` (`railMenuDisabled`) means every rail item is always visible — there is no menu —
  and tapping the app icon instead folds the rail away (AzNavRail 11.0's `noMenu` behaviour, see
  `docs/AZNAVRAIL_COMPLETE_GUIDE.md`). Both now say "Tap 'Open' on the rail" instead.
- ~~README claims snap-back is an opt-in toggle, off by default~~ — **re-checked, not a bug.**
  `setRelocEnabled`/`mRelocEnabled` (always-on, no caller) only gates the native engine's
  *computation* of a relocalized pose; whether the app actually *uses* that pose is
  `SettingsRepository.driftCorrectionEnabled`, which maps directly to `ArRenderer.fusionEnabled`
  and defaults **off**. So the user-visible "does the overlay snap back" behavior genuinely is
  opt-in, off by default, exactly as README.md describes — the dead `setRelocEnabled` flag is real
  vestigial API-surface (worth deleting) but not a docs bug.
- ~~`gEngineMutex` (JNI) serializes the GL/render thread behind full-resolution OpenCV work~~ —
  **fixed.** `gEngineMutex`'s own doc comment establishes it only ever protected the *lifetime* of
  the `gSlamEngine` pointer (against `nativeDestroy`'s `delete` racing a dereference) — not
  `MobileGS`'s internal state, which is already its own job (`mMutex`/`mRelocMutex`/`std::atomic`
  members). So it's now a `std::shared_mutex`: `nativeInitialize`/`nativeDestroy` (which write the
  pointer) and `nativeLoadSuperPoint`/`nativeLoadDistortionHead`/`nativeLoadLowLightEnhancer`
  (which mutate `mSuperPoint`/`mDistortionHead`/`mEnhancer` in place, read by several paths with no
  lock of their own) take `std::unique_lock`; the other 44 entry points take `std::shared_lock` and
  can now run concurrently — the actual fix for heavy OpenCV/SuperPoint work blocking the
  per-frame camera path behind one global lock. The one real hazard this exposed —
  `MobileGS::scheduleRelocCheck`'s `mRelocViewMatrix` snapshot relied on `gEngineMutex`'s
  incidental global exclusivity to stay race-free against `updateCamera`'s writes to
  `mViewMatrix`, rather than any `MobileGS`-internal lock — is fixed: it now takes its own
  `mMutex`-guarded snapshot first, matching `updateCamera`'s writer lock, non-nested with
  `mRelocMutex` per the file's existing pattern. Verified two ways: `tools/check_native_locking.py`
  (new — a static lock-discipline checker that flags any plain `MobileGS` member touched with no
  engine-owned lock active in scope; it found the three live-reloadable model members above and,
  by design, does *not* catch "wrong lock held" bugs like the `mRelocViewMatrix` one, which was
  fixed by hand) and a real native/CMake build (`arm64-v8a` + `armeabi-v7a`) plus the full unit
  suite including `NativeMethodAritySignatureTest`, all green. Not device- or TSan-verified — no
  device or sanitizer tooling is available in this sandbox; the fix is proven by the documented
  reasoning above and the static check, not by observed absence of a race under real load.
  **Follow-up fix:** the conversion missed one JNI-level global outside the checker's reach —
  `gLastColorFrame` (`GraffitiJNI.cpp`), written by both `nativeFeedYuvFrame` and
  `nativeFeedColorFrame`, both of which now hold only `gEngineMutex`'s shared lock and can run
  concurrently (e.g. the normal camera feed racing a glasses-session feed). A glee audit caught
  this immediately after the shared_mutex PR merged. Fixed with a dedicated `gColorFrameMutex`,
  held only around the read-modify-write of `gLastColorFrame` itself (assign, then take a cheap
  header-copy snapshot) — never around the heavy YUV/RGBA conversion or reloc-frame build that
  follows, so the fix doesn't reintroduce the stall the original conversion removed. Also
  corrected the `gEngineMutex` comment's false claim that it was "the only thing" serializing a
  live model reload against the background reloc-thread worker — that worker never takes
  `gEngineMutex` at all (it's a `std::thread`, not a JNI entry point); the actual protection is
  each ONNX wrapper class's own internal `mMutex`. `tools/check_native_locking.py`'s docstring
  now states plainly what it does and doesn't cover (JNI-level globals and classes outside
  `MobileGS.cpp` are both invisible to it) rather than implying a clean run proves more than it
  does.
- **A dead AI-glasses subsystem is still fully wired** — `startGlassesSession` has zero callers and
  `WearableModule`'s only bound provider can never match its "Meta" name lookup, despite
  README.md:60 describing the subsystem as already removed. **Re-checked, not a simple deletion**:
  it's ~640 LOC (per this file's own earlier "Still open" note above), not ~400, and it's tracked
  there as deliberate WIP (`glassesWorldHitForTimestamp` needs a real glasses-side world lookup —
  substantial new native/SDK integration — not dead code with no intent behind it). Deleting it
  reverses that prior decision; that's a product call, not a cleanup. Left for the repo owner:
  either commit to finishing it or explicitly kill it — either way, fix the README claim to match
  whichever is chosen.
- ~~Live docs still describe the deleted voxel/splat engine, a stencil generator with no source
  files, and other removed features as shipping~~ — **fixed.** All core English docs
  (`ARCHITECTURE.md`, `BLUEPRINT.md`, `file_descriptions.md`, `performance.md`, `testing.md`,
  `contributing.md`, `API_REFERENCE.md`, `AUDIT.md`, `FUTURE_STRATEGY.md`, `FEATURE_REFERENCE.md`,
  `en/USER_FLOW.md`, `en/screens.md`, `misc.md`, `RELOC_MAP_DESIGN.md`, `STENCILS.md`,
  `en/PRIVACY_POLICY.md`), the live marketing site (`index.html`, `fr/index.html`), and every
  localized doc (13 languages) have been corrected and swept clean by direct grep verification —
  see the doc-accuracy-pass PRs.

Conceptual, needs a product decision rather than a code fix:

- **The feature that most directly solves "get the design onto the wall without holding the
  phone up" is unshipped**: stencil/tiled-PDF export has no implementation despite being marketed
  on the live site and documented in `docs/STENCILS.md`/`docs/FEATURE_REFERENCE.md` as if it
  ships. (Trace mode's Freeze — the touchscreen lock — already covers "hold the design still";
  the `onFreezeRequested` chain once mistaken for a second such feature was dead diagnostic code
  and has been deleted, see above.)
- **No way anywhere in the app to enter real wall dimensions** — scale comes from a depth guess
  adjusted by pinch, for a target user (commissioned muralist) who prices per square foot.
- **The "no cloud" anti-goal blocks encrypted crew fingerprint-sharing** (Co-op stays host→guest
  only) without changing the risk profile it's meant to protect (an E2E relay holding no identity
  would serve the stated illegal-graffiti threat model equally well).
- Five "modes" (AR/Overlay/Mockup/Trace/Design) are one renderer with three boolean axes
  (background source × world-locked × editable) wearing five names and duplicating adjustment
  state per mode.
