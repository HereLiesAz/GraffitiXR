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

### Todo

- **CodeQL #3/#4/#5 — Inclusion of functionality from untrusted source** (`docs/index.html:8–10`). Three `<script src="https://cdnjs.cloudflare.com/...">` tags (rellax 1.12.0, gsap 3.12.2, ScrollTrigger 3.12.2) load without integrity checks.
  - **Action A (preferred):** add `integrity="sha384-…"` + `crossorigin="anonymous"` to each tag. Hashes are published on the cdnjs page for each library version.
  - **Action B:** vendor the three files under `docs/vendor/` and reference local paths. Eliminates the alert and removes a runtime CDN dependency.
  - **Note:** line 7 (`https://cdn.tailwindcss.com`) is the tailwind play-CDN, which intentionally has no stable SRI hash. If it shows up as an alert, prefer Action B (vendor a built Tailwind CSS) since SRI is impractical here.

  (The Bouncy Castle advisories #23/#24/#25 previously listed here are resolved — see the `1.84` force in the Done section above.)

## Dead / unreachable features (audited, deliberately left as-is — decide per feature)

A codebase audit found these fully-built-but-unreachable or non-functional features. They were **not** changed in the bug-squash pass (only outright bugs were), and are catalogued here for a future product decision (wire up vs. remove):

- **Glasses AR session** — no entry point invokes `ArViewModel.startGlassesSession()` from `Idle` (only the post-`Active` reconnect banner does), and calibration (`ArViewModel.glassesWorldHitForTimestamp`, ~`:388`) hit-tests the same screen point for `src`/`dst`, so `Procrustes.solve` always returns identity.
- **AR tap-to-distance UI** — reticle + per-mark distance chips are gated on `arUiState.isDepthApiSupported`, but the ARCore Depth API is deliberately disabled (`useArCoreDepthApi = false`, `ArViewModel` ~`:859`) because it starved VIO on the target hardware. The UI never renders. (Re-gating on the VIO/stereo depth that *is* available would revive it.)
- **AR freeze-preview** — `ArViewModel.onFreezeRequested()` is never called, so `FreezePreviewScreen` and the unfreeze→`toggleImageLock` chain are dead.
- **Per-mode transform lock** — `EditorViewModel.onToggleModeTransformLocked` and four siblings are never called from UI; `ModeAdjustment.isTransformLocked` is always false.
- **`EditorMode.STENCIL`** — `MainActivity` immediately bounces STENCIL→MOCKUP and no route targets it; stencil generation is reachable only via the per-layer action.
- **Empty placeholder screens** — `MockupScreen`/`OverlayScreen`/`TraceScreen` ignore all params (real rendering is in `MainScreen`).
- **Unwired functions / dead classes** — `ArViewModel.requestExport`/`updateMaskPath`/`setPlaneConfirmationBorder`/`applyEraseToMask`; `CrashHandler` + unregistered `CrashActivity`; `WarpableImage`/`VirtualCamera`/`SurfaceUnroller`; `CollaborationManager.stopHosting()`; the dead `Tool.LIQUIFY` branch in `ImageProcessor.applyToolToBitmap`.
- **Orphaned Vulkan splat shaders** — `core/nativebridge/src/main/assets/shaders/splat.{vert,frag}` are `#version 450` Vulkan GLSL; the Vulkan backend was removed and nothing loads them (dead APK weight).
- **Minor** — `LocalIp` naively picks the first non-loopback IPv4 (can advertise an unreachable VPN/cellular address); `ImageProcessingUtils.convertYuvToRgbaDirect` round-trips through a Bitmap despite advertising zero-copy.
