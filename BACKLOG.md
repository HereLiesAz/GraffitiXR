# Backlog

## Security alerts

### Done

- **CodeQL #6 — Multiplication result converted to larger type** (`core/nativebridge/src/main/cpp/SurfaceUnroller.cpp:100`). `mCount * mCount` was computed as `int` and then widened to `size_t` for the `std::vector` ctor; overflows for `mCount ≥ 46341`. Fixed by casting both operands to `std::size_t` before multiplying.
- **Dependabot #27 — Netty HTTP header injection in HttpProxyHandler** (`io.netty:netty-handler-proxy`). Fixed by forcing the netty family (incl. `netty-handler-proxy`) to `4.2.15.Final` in `build.gradle.kts` `commonForcedDependencies`. **Verified:** 4.2.15.Final addresses CVE-2025-67735 and subsequent regressions.

- **Dependabot #23/#24/#25 — Bouncy Castle (covert timing channel / LDAP injection / risky crypto algo).** Fixed by forcing `org.bouncycastle:{bcprov,bcpkix,bcutil}-jdk18on:1.84` in `build.gradle.kts` `commonForcedDependencies` (1.84 first patches all three; the three artifact versions must match).

- **Task 17 — Co-op Implementation.** Real project serialization and spectator load implemented in `ProjectManager`. Wired `StrokeComplete` Op in `EditorViewModel` using bitmap-space mapping. Verified host-guest drawing sync logic.
- **Co-op — Transport security & robustness.** Encrypted the peer-to-peer transport (protocol v2: token-derived AES-256-GCM per frame, nonce/proof handshake, no token on the wire) and hardened the sessions: accept-loop survives bad handshakes, ops are lossless across reconnects (seq/encode/buffer at enqueue), 15s socket read timeouts, guest re-syncs on host `sessionId` change, bounded pre-handler spectator-op buffering. Import/spectator load hardened against Zip-Slip. See `collab/` and `core/data/ProjectManager.kt`.
- **Relocalization — Fingerprint JNI ABI.** Fixed `nativeSetWallFingerprint` silently returning null (native looked up a stale `Fingerprint` constructor descriptor after a field was added). Native now builds through a frozen `Fingerprint.fromNative` factory guarded by `FingerprintJniContractTest`.
- **Task 15 — QR Scanner.** Integrated ZXing-based QR scanner into `MainActivity`. Search button now triggers live scanner to join sessions.
- **Voxel Memory — Frustum Culling.** Implemented true NDC-based visible splat confidence calculation in `VoxelHash`.
- **Relocalization — Thread Safety.** Added mutex locking to `mWallDescriptors` and `mWallKeypoints3D` in `MobileGS` to prevent races between JNI updates and the background PnP thread.

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

### Todo

- **CodeQL #3/#4/#5 — Inclusion of functionality from untrusted source** (`docs/index.html:8–10`). Three `<script src="https://cdnjs.cloudflare.com/...">` tags (rellax 1.12.0, gsap 3.12.2, ScrollTrigger 3.12.2) load without integrity checks.
  - **Action A (preferred):** add `integrity="sha384-…"` + `crossorigin="anonymous"` to each tag. Hashes are published on the cdnjs page for each library version.
  - **Action B:** vendor the three files under `docs/vendor/` and reference local paths. Eliminates the alert and removes a runtime CDN dependency.
  - **Note:** line 7 (`https://cdn.tailwindcss.com`) is the tailwind play-CDN, which intentionally has no stable SRI hash. If it shows up as an alert, prefer Action B (vendor a built Tailwind CSS) since SRI is impractical here.

  (The Bouncy Castle advisories #23/#24/#25 previously listed here are resolved — see the `1.84` force in the Done section above.)

(Dead-features and export/YUV items cleared — see the notes under **Done** above. Remaining open items: Glasses AR session, AR freeze-preview.)
