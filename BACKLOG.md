# Backlog

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

#### Dead-features clearance pass

The audit's "Dead / unreachable features" section is closed. Actions:

- **Deleted** (~700 LOC): `CrashHandler` + `CrashActivity` (unregistered), the empty placeholder `MockupScreen`/`OverlayScreen`/`TraceScreen` (real rendering is in `MainScreen`), `MaskingScreen` + `ArViewModel.updateMaskPath` + `UiState.maskPath` (unrouted), `ArViewModel.setPlaneConfirmationBorder` + the renderer flag (dead), `ArViewModel.applyEraseToMask` (redundant with `TargetCreationFlow.eraseColorBlob`), `CollaborationManager.stopHosting()` (`leaveSession` is a strict superset), the `Tool.LIQUIFY` branch + `applyLiquifyNative` stub in `ImageProcessor` (unreachable — `DrawingEngine.composite` routes LIQUIFY through `SlamManager.applyLiquify`), Kotlin `WarpableImage`/`VirtualCamera`/`SurfaceUnroller` research code, Vulkan `splat.{vert,frag}[.spv]` assets (Vulkan backend removed), and the four unused transform-lock companion helpers in `EditorViewModel`.
- **Deleted enum:** `EditorMode.STENCIL` — auto-bounced to MOCKUP and had no route; per-layer stencil generation (`onGenerateStencil`) is untouched.
- **Wired up:** AR tap-to-distance UI now gates on `(isDualLensActive || currentCenterDepth > 0f)` instead of the deliberately-disabled ARCore Depth API — the reticle + distance chips light up on capable devices. Per-mode transform-lock toggle: a **Lock** rail sub-item under each non-Design mode folder (AR/OVERLAY/MOCKUP/TRACE), turning cyan when engaged — the reducer already respected `isTransformLocked`.
- **Fixed:** `LocalIp.discover()` now picks the source address of the default route via the UDP-connect trick, so QR pairing advertises a LAN-reachable IP on multi-interface (cellular + Wi-Fi, VPN) devices.

Still open, not touched this pass:

- **Glasses AR session** — ~640 LOC of overlays + calibration exists, but `glassesWorldHitForTimestamp` hit-tests the same phone screen point for src/dst so Procrustes always returns identity. A real fix needs a glasses-side world lookup — substantial new native/SDK integration. Left as WIP.
- **AR freeze-preview** — `onFreezeRequested`/`FreezePreviewScreen`/`unfreezeRequested` chain is complete but nothing calls `onFreezeRequested`. Held pending a UX decision vs. the transform-lock (which covers a similar "hold the design still" intent for many use cases).

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

Remaining open items (all in `docs/AUDIT.md` under "Still open"): Glasses AR session, AR
freeze-preview, bidirectional co-op, and a short list of unreferenced diagnostic/eval knobs.

#### Glee audit pass (2026-09-04) — not yet acted on

Correctness bugs, worst first:

- **Relocalization status chip can never render** — `MainActivity.kt`'s gate condition
  (`!arUiState.isAnchorEstablished`) and the state computation's own early-return
  (`if (relocState == RelocState.IDLE) return`) are mutually exclusive; `SEARCHING`/`TRACKING`
  are dead branches.
- **First-run AR instruction tells the user to tap the icon that hides the rail** — the
  `showDesignInstructionsDialog` copy ("Tap the menu icon, then tap 'Open'") targets a `noMenu`
  rail whose icon collapses it, not opens it.
- ~~README claims snap-back is an opt-in toggle, off by default~~ — **re-checked, not a bug.**
  `setRelocEnabled`/`mRelocEnabled` (always-on, no caller) only gates the native engine's
  *computation* of a relocalized pose; whether the app actually *uses* that pose is
  `SettingsRepository.driftCorrectionEnabled`, which maps directly to `ArRenderer.fusionEnabled`
  and defaults **off**. So the user-visible "does the overlay snap back" behavior genuinely is
  opt-in, off by default, exactly as README.md describes — the dead `setRelocEnabled` flag is real
  vestigial API-surface (worth deleting) but not a docs bug.
- **`gEngineMutex` (JNI) serializes the GL/render thread behind full-resolution OpenCV work** —
  target-capture fingerprint generation and SuperPoint inference hold the same global lock the
  per-frame camera/YUV callbacks need, which is a likely visible freeze at exactly the moment the
  artist confirms a target. Fixing it to a `shared_mutex` needs care: `MobileGS.cpp`'s
  `mRelocViewMatrix` copy is *only* race-free today because `gEngineMutex` happens to also
  serialize it against `updateCamera`'s writes to `mViewMatrix` — a plain reader/writer split would
  reintroduce that torn read. Needs its own lock, not just relaxing the JNI one.
- **A dead AI-glasses subsystem is still fully wired** (~400 LOC: `startGlassesSession` has zero
  callers, `WearableModule`'s only bound provider can never match the "Meta" name lookup) despite
  README.md:60 describing it as already removed. Either delete it or actually remove it.
- **Live docs (`docs/BLUEPRINT.md`, `docs/index.html`, 9+ translated `docs/*/README.md`, and most of
  `docs/testing.md`/`docs/performance.md`/`docs/ARCHITECTURE.md`/`docs/FEATURE_REFERENCE.md`) still
  describe the deleted voxel/splat engine, a stencil generator with no source files, and other
  removed features as shipping.** The 2026-09-04 correction pass touched 5 files; ~25 more still
  contradict them. Full list with file:line citations is in the docs-cross-consistency audit
  transcript.

Conceptual, needs a product decision rather than a code fix:

- **The two features that most directly solve "get the design onto the wall without holding the
  phone up" are unshipped**: `onFreezeRequested`'s freeze-to-paint chain has no caller (see the
  existing WIP note above), and stencil/tiled-PDF export has no implementation despite being
  marketed on the live site and documented in `docs/STENCILS.md`/`docs/FEATURE_REFERENCE.md` as if
  it ships.
- **No way anywhere in the app to enter real wall dimensions** — scale comes from a depth guess
  adjusted by pinch, for a target user (commissioned muralist) who prices per square foot.
- **The "no cloud" anti-goal blocks encrypted crew fingerprint-sharing** (Co-op stays host→guest
  only) without changing the risk profile it's meant to protect (an E2E relay holding no identity
  would serve the stated illegal-graffiti threat model equally well).
- Five "modes" (AR/Overlay/Mockup/Trace/Design) are one renderer with three boolean axes
  (background source × world-locked × editable) wearing five names and duplicating adjustment
  state per mode.
