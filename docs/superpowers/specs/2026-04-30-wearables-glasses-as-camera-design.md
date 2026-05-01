# Wearables: Glasses-as-Camera (Meta Ray-Ban) with Tap Calibration

**Status:** Spec 3 of 3 (sequential). Predecessors: AzNavRail 8.9 migration; co-op real-time spectator. Successor: none.

**Date:** 2026-04-30
**Owner:** Az
**Module:** `core/common/.../wearable/` (rewritten); touches `feature/ar`, `app/`.

---

## 1. Goal

Take the existing wearables framework (currently a stubbed provider abstraction with the rail item callback wired to nothing) and ship a working glasses-as-camera flow for Meta Ray-Ban: glasses' camera and IMU drive the AR pipeline, ARCore on the phone is used in parallel for plane/depth detection, and a one-shot tap calibration aligns the two world frames. While doing this, tighten `SmartGlassProvider` from a connection-state interface into a real data-source interface so adding Xreal (and future devices) later is a plug-in, not a redesign.

## 2. Non-goals

- Implementing Xreal as an active data source. `XrealGlassProvider` conforms to the new interface but emits empty Flows and stays in framework-only state. Future spec.
- Continuous (drift-correcting) co-localization between phone and glasses. One-shot tap calibration is the chosen approach.
- A Wear OS smartwatch companion module.
- Glasses-as-display (the inverse direction). Out of scope by user choice in brainstorming.
- Bidirectional capture+display.
- Glasses support outside AR mode. Trace/Overlay/Mockup are not in scope (the live-camera contribution is AR-specific).
- Replacing ARCore. ARCore stays in its current role for plane detection and depth.

## 3. Background

### 3.1 What exists today

`core/common/.../wearable/`:
- `SmartGlassProvider.kt` — interface with `name`, `capabilities: Set<GlassCapability>`, `connectionState: StateFlow<ConnectionState>`, `connect()`, `disconnect()`, `startRegistration(activity)`.
- `WearableManager.kt` — `@Singleton` Hilt orchestrator that takes `Set<SmartGlassProvider>`. Currently selects/coordinates which provider is active.
- `MetaGlassProvider.kt` — wraps `com.meta.wearable.dat.core.Wearables` SDK; `startRegistration` defers to the Meta companion app for pairing.
- `XrealGlassProvider.kt` — USB device detection only (VID 0x3318, 0x0483). No camera or IMU pipeline.

`GlassCapability` declares `CAMERA_FEED`, `SPATIAL_DISPLAY`, `IMU_TRACKING`, `HAND_TRACKING`. The capability flags exist; no code reads frames or IMU samples through the provider.

`MainActivity.kt:1065-1073`: the `wearable` rail sub-item has an empty `{}` callback. The Meta SDK is initialized at app start (`Wearables.initialize(this)` in `GraffitiApplication`).

The SLAM stack is `SlamManager` (Kotlin) with a JNI/native engine (MobileGS, SuperPoint, Zero-DCE++ for low-light). Phone camera frames feed it today.

### 3.2 What changes

1. `SmartGlassProvider` becomes a data-source interface: it exposes `cameraFrames: Flow<Frame>` and `imuSamples: Flow<ImuSample>` plus camera intrinsics, in addition to the existing connection-state surface.
2. `MetaGlassProvider` is filled in to actually emit camera frames and IMU samples from the Meta SDK.
3. `WearableManager` gains a "glasses session" concept: when a provider is `Connected` and the user activates glasses-as-camera, the manager exposes the active provider's flows to consumers.
4. `SlamManager` accepts an external sensor source — a `SensorSource` interface that can be the phone (existing) or the glasses (new).
5. ARCore continues running on the phone (in the user's off-hand) for plane detection. Its anchors are transformed into the glasses world frame via the calibrated phone↔glasses transform.
6. A one-shot tap calibration UI computes the phone↔glasses transform at session start and on user request.
7. The empty `wearable` rail callback is wired to a pairing overlay → calibration overlay → active session lifecycle.
8. Glasses disconnect mid-session triggers automatic fallback to phone sensors (the SensorSource swap is the existing path; the system was using it before glasses connected).

## 4. Architecture

### 4.1 Three new abstractions in `core/common`

```kotlin
// core/common/.../sensor/SensorSource.kt (new)
interface SensorSource {
    val cameraFrames: Flow<CameraFrame>      // YUV/RGB + timestamp + intrinsics
    val imuSamples: Flow<ImuSample>           // gyro + accel + timestamp
    val cameraIntrinsics: CameraIntrinsics   // fx, fy, cx, cy, width, height
}

// core/common/.../sensor/CameraFrame.kt (new)
data class CameraFrame(
    val pixels: ByteBuffer,                   // backing buffer (zero-copy from upstream when possible)
    val format: PixelFormat,                  // YUV_420_888 | RGBA_8888
    val width: Int, val height: Int,
    val timestampNs: Long,
)

// core/common/.../sensor/ImuSample.kt (new)
data class ImuSample(
    val gyro: Vec3,                           // rad/s
    val accel: Vec3,                          // m/s²
    val timestampNs: Long,
)
```

`SmartGlassProvider` (existing, modified) extends `SensorSource` for providers that have `CAMERA_FEED` and `IMU_TRACKING` capabilities. For providers missing those capabilities, the corresponding Flows are empty and `cameraIntrinsics` returns `CameraIntrinsics.UNKNOWN`. Providers not exposing sensor data still satisfy the type system.

A new `PhoneSensorSource` (in `core/common`, possibly delegating to existing `feature/ar` code) wraps the phone camera + IMU as the same interface. The SLAM stack now consumes a `SensorSource`, not a phone-specific source.

### 4.2 Pipeline diagram

```
                 ┌─────────────────────────────────────────────────┐
                 │                  UI / EditorViewModel             │
                 └────────────┬───────────────────┬──────────────────┘
                              │                   │
                          (anchors)            (overlay rendering)
                              │                   │
                ┌─────────────▼─────────────┐    │
                │     ArViewModel             │    │
                │  - holds glassesActive Bool │    │
                │  - holds phoneToGlassesXform│    │
                └─────┬──────────────┬────────┘    │
                      │              │             │
              (phone cam+imu)   (glasses cam+imu)  │
                      │              │             │
              ┌───────▼──┐   ┌───────▼───────┐    │
              │ ARCore    │   │  SlamManager   │    │
              │ session   │   │  (consumes      │    │
              │ (phone)   │   │   active        │    │
              │           │   │   SensorSource) │    │
              └────┬──────┘   └───────┬─────────┘    │
                   │                  │              │
              (planes,                (glasses pose) │
               depth                                 │
               in phone                              │
               frame)                                │
                   │                  │              │
                   └────► transform ──►              │
                       (phoneToGlassesXform)         │
                                  │                  │
                              (planes, depth         │
                               in glasses frame)    │
                                  │                  │
                                  └─────► render ◄───┘
```

ARCore continues to track the phone (in off-hand). SLAM tracks glasses pose using glasses sensors. ARCore's planes/anchors are projected into the glasses frame via `phoneToGlassesXform`. The renderer uses glasses pose as the camera position.

### 4.3 Calibration

`phoneToGlassesXform: SE(3)` is computed via one-shot tap calibration:

1. User points phone camera at the wall and ensures the wall is also in the glasses' camera view.
2. User taps the wall (single tap on the phone screen, hit-tested against ARCore plane).
3. Phone records the tap point `p_phone ∈ W_phone` (world frame in ARCore).
4. SLAM, simultaneously, records the corresponding feature observation in glasses frame as `p_glasses ∈ W_glasses` (using the tap timestamp + the glasses-side feature track that intersects the camera ray to the wall at that timestamp).
5. With one point and gravity (both systems are gravity-aligned), four DoF are constrained. To pin down full 6 DoF, the calibration requires a small motion sequence after the tap: user holds the tap point in view for ~1s while moving slightly. The system collects ~10-20 paired observations, then solves the rigid transform via Procrustes / closed-form least squares.
6. Transform is cached for the session. User-triggered "Recalibrate" (a rail item or banner action) repeats the procedure.

Calibration is mandatory at glasses-session start. Without it, ARCore data is not projected into the glasses frame (anchors and planes simply don't render) and the user sees only what SLAM tracks.

### 4.4 Glasses session lifecycle

| Event | State transition |
|---|---|
| User taps `wearable.main` rail item | Idle → `PairingPrompt` overlay (Meta SDK companion app pairing) |
| Pairing succeeds, provider becomes `Connected` | `PairingPrompt` → `CalibrationPrompt` overlay |
| User completes tap calibration | `CalibrationPrompt` → `Active` (glasses session running) |
| User taps "Recalibrate" | `Active` → `CalibrationPrompt` → `Active` |
| Provider transitions to `Error` or `Disconnected` mid-session | `Active` → `Fallback` (phone sensors only); banner shows; rail item reflects state |
| User taps `wearable.main` to end session | Any → Idle |

### 4.5 Provider abstraction shape (final)

```kotlin
interface SmartGlassProvider : SensorSource {
    val name: String
    val capabilities: Set<GlassCapability>
    val connectionState: StateFlow<ConnectionState>
    suspend fun connect()
    suspend fun disconnect()
    fun startRegistration(activity: Activity)

    // From SensorSource: cameraFrames, imuSamples, cameraIntrinsics.
    // Implementations without CAMERA_FEED return emptyFlow().
    // Implementations without IMU_TRACKING return emptyFlow().
}
```

`MetaGlassProvider` implements the sensor methods backed by Meta SDK callbacks. `XrealGlassProvider` returns empty flows + `CameraIntrinsics.UNKNOWN` until a future spec implements its USB camera path.

## 5. Components

| File | Change |
|---|---|
| `core/common/.../sensor/SensorSource.kt` (new) | Interface above. |
| `core/common/.../sensor/CameraFrame.kt` (new) | Data class above. |
| `core/common/.../sensor/ImuSample.kt` (new) | Data class above. |
| `core/common/.../sensor/CameraIntrinsics.kt` (new) | `data class CameraIntrinsics(fx: Float, fy: Float, cx: Float, cy: Float, width: Int, height: Int)` plus a sentinel `UNKNOWN`. |
| `core/common/.../sensor/PhoneSensorSource.kt` (new) | Wraps the existing phone camera (CameraX or whatever the AR feature uses) + the device IMU into a `SensorSource`. Likely delegates into existing code in `feature/ar`. |
| `core/common/.../wearable/SmartGlassProvider.kt` | Modify: extends `SensorSource`. Default empty implementations of `cameraFrames` / `imuSamples` / `cameraIntrinsics` so existing impls compile. |
| `core/common/.../wearable/MetaGlassProvider.kt` | Implement camera frame Flow from Meta SDK camera callback. Implement IMU sample Flow. Provide intrinsics from SDK metadata (or hardcoded fallback if SDK doesn't expose them). |
| `core/common/.../wearable/XrealGlassProvider.kt` | Override sensor methods with empty flows and `UNKNOWN` intrinsics. Connection-state surface unchanged. |
| `core/common/.../wearable/WearableManager.kt` | Add `activeSensorSource: StateFlow<SensorSource>` exposing the connected glass-provider's flows when in glasses session, otherwise the `PhoneSensorSource`. |
| `feature/ar/.../SlamManager.kt` | Modify to consume a `SensorSource` injected by Hilt instead of holding camera/IMU references directly. Hot-swappable: if the injected source's flows complete (glasses disconnect), the SLAM stack rebinds to `PhoneSensorSource`. |
| `feature/ar/.../ArViewModel.kt` | Add `glassesSessionState: StateFlow<GlassesSessionState>`. Add methods: `startGlassesSession()`, `endGlassesSession()`, `recalibrate()`, `submitCalibrationTap(screenPoint)`. The model holds `phoneToGlassesXform` (a `Matrix4` or `Mat4`). When glasses session is active, ARCore anchors are post-multiplied by this transform before being passed to the renderer. |
| `feature/ar/.../GlassesSessionState.kt` (new) | `sealed class GlassesSessionState { Idle; PairingPrompt; CalibrationPrompt(progress); Active; Fallback(reason) }`. |
| `app/.../MainActivity.kt:1065-1073` (`wearable.main` rail item) | Replace empty callback. Idle → `arViewModel.startGlassesSession()`. Active/Fallback → `arViewModel.endGlassesSession()`. Label/color reflect typed state. |
| `app/.../GlassesPairingOverlay.kt` (new) | Compose overlay shown during `PairingPrompt`. Calls `provider.startRegistration(activity)` and observes `connectionState` for transitions. |
| `app/.../CalibrationOverlay.kt` (new) | Compose overlay shown during `CalibrationPrompt`. Instructional UI ("Hold the phone so it sees the wall. Tap the wall in the camera view."). On tap, calls `arViewModel.submitCalibrationTap(point)`. Shows a progress bar for the post-tap motion-sequence collection. |
| `app/.../GlassesStatusBanner.kt` (new) | Small persistent banner during `Active` and `Fallback`. In `Fallback` includes a "Reconnect glasses" action. |
| `feature/ar/build.gradle.kts` | If a numerical library for Procrustes solve isn't present, add one (`org.jblas` or use a small hand-rolled SVD). |
| `core/common/build.gradle.kts` | Verify Meta SDK deps stay; no change expected. Drop any unused mock-device dep if not used by tests. |
| `app/.../GraffitiApplication.kt` | Verify `Wearables.initialize(this)` survives. No structural change. |
| Tests (new, in `core/common/src/test`, `feature/ar/src/test`, `app/src/test`) | Per §8. |

Module dependency direction: `app` → `feature/ar` → `core/common`. The AR feature depends on the abstract `SensorSource`. The wearables module sits in `core/common` and provides concrete `SensorSource` implementations.

## 6. Data flow

### 6.1 Frame path

```
Phone camera (off-hand)        Glasses camera (face)
       │                              │
       ▼                              ▼
  PhoneSensorSource               MetaGlassProvider
   .cameraFrames                   .cameraFrames
       │                              │
       ▼                              ▼
   ARCore session              SlamManager.feedFrame()
       │                              │
       ▼                              ▼
   planes, depth,                glasses pose
   anchors in W_phone             in W_glasses
       │                              │
       │                              │
       └─── phoneToGlassesXform ──────┤
                  │                   │
                  ▼                   ▼
           planes, anchors        camera position
           in W_glasses           for renderer
                  │                   │
                  └───────┬───────────┘
                          ▼
                   Compose render
                  (overlay + AR)
```

### 6.2 Calibration path

```
1. User taps phone screen at point P_screen
2. ArViewModel.submitCalibrationTap(P_screen):
   a. ARCore hit-test → P_phone ∈ W_phone (3D wall point in phone's frame)
   b. SLAM fetches the camera ray for the same timestamp from the glasses;
      back-projects through the SLAM map to find the wall intersection → P_glasses ∈ W_glasses
   c. Begin motion-sequence collection: for the next ~1s (or 20 sample pairs),
      every 50ms record (P_phone_t, P_glasses_t) by re-hit-testing the same
      tracked feature in both frames
   d. Solve T such that minimizes Σ ||P_glasses_t - T · P_phone_t||² over the sample set
      (closed-form Procrustes; 4 DoF + gravity → 6 DoF)
   e. Store T as phoneToGlassesXform
   f. Emit GlassesSessionState.Active
```

### 6.3 Disconnect / fallback path

```
MetaGlassProvider.connectionState transitions to Disconnected or Error
       │
       ▼
WearableManager swaps activeSensorSource → PhoneSensorSource
       │
       ▼
SlamManager observes Flow completion on previous source; rebinds to new source
       │
       ▼
ArViewModel.glassesSessionState = Fallback(reason)
       │
       ▼
GlassesStatusBanner shows; user may tap "Reconnect"
```

In `Fallback`, the renderer uses ARCore pose (phone) as the camera. Behavior is identical to phone-only mode pre-glasses.

## 7. Error handling

### 7.1 Eliminated

| Failure | How it dies |
|---|---|
| Empty `wearable.main` callback (`MainActivity.kt:1073`) | Wired to `arViewModel.startGlassesSession()` / `endGlassesSession()`. |
| `WearableManager` unused despite being `@Singleton` injected | Becomes the orchestrator for `activeSensorSource`. Compile-time references via Hilt DI. |
| Glasses provider claiming `CAMERA_FEED` capability but exposing no frames | New interface forces all providers to declare a Flow for camera frames; impls without the capability return `emptyFlow()` explicitly (and `cameraIntrinsics = UNKNOWN`). The flag and the implementation can no longer drift. |

### 7.2 Tolerated, made graceful

| Failure | Behavior |
|---|---|
| Calibration tap doesn't hit a plane in ARCore | Calibration overlay shows "Move closer to the wall and tap a flat surface." No state transition. |
| Calibration motion sequence doesn't produce enough valid samples (e.g., glasses lose tracking) | Calibration aborts with retry prompt. Session remains in `CalibrationPrompt`. |
| Procrustes solve produces a transform with implausible scale (validates via `‖T.scale - 1‖ > 0.05`) | Reject; ask user to recalibrate. |
| Glasses disconnect mid-session | Auto-fallback to phone sensors (§6.3). User keeps painting. |
| ARCore loses tracking (phone moved into pocket / dark) | Glasses pose still flows from SLAM. The `phoneToGlassesXform` is stale but the canvas still renders from glasses pose; planes/depth from ARCore stop updating. Banner: "Phone tracking lost — point phone at the wall to restore plane detection." |
| Both ARCore and SLAM lose tracking simultaneously | AR rendering pauses; user prompted to reset. |
| Meta SDK pairing fails or is cancelled | Return to Idle. UI shows the SDK's failure reason (passed through verbatim). |
| Frame format mismatch (provider emits unexpected pixel format) | Log + drop the frame. SLAM continues with prior frame. After 1s of consecutive drops, fall back to phone source. |
| IMU samples arrive out-of-order (timestamp regression) | Drop the regressing sample. Log if frequency exceeds 1Hz. |

### 7.3 Out of scope

- Glasses display feedback (we send nothing back to the glasses).
- Multi-glasses sessions (one provider active at a time).
- Calibration accuracy quantification beyond the Procrustes residual rejection.
- Recovery from arbitrary SLAM divergence; that's the existing SLAM stack's domain.

## 8. Testing

### 8.1 Unit tests

| Test | Asserts |
|---|---|
| `SensorSourceContractTest` | Every `SensorSource` impl emits well-formed `CameraFrame` and `ImuSample` objects with monotonically non-decreasing timestamps; intrinsics are non-zero or explicitly `UNKNOWN`. |
| `MetaGlassProviderFrameAdapterTest` | Mock the Meta SDK callback; assert `cameraFrames` Flow emits one `CameraFrame` per SDK callback with correctly mapped fields. |
| `CalibrationSolverTest` | Given synthetic paired points with a known ground-truth transform plus Gaussian noise, the Procrustes solve recovers the transform within tolerance. Implausible-scale rejection fires for degenerate inputs. |
| `WearableManagerSwapTest` | When the active provider transitions to `Disconnected`, `activeSensorSource` rebinds to `PhoneSensorSource` and downstream consumers see Flow completion + new emissions. |
| `GlassesSessionStateMachineTest` | `Idle → PairingPrompt → CalibrationPrompt → Active → Fallback → Active` transitions; illegal transitions throw. |
| `XformProjectionTest` | An anchor in `W_phone`, projected through `phoneToGlassesXform`, lands at the expected `W_glasses` coordinate to within tolerance. |

### 8.2 Integration tests

| Test | Asserts |
|---|---|
| `MockProviderEndToEndTest` | A fake `SmartGlassProvider` emits scripted frames + IMU samples. Run the full session lifecycle with stubbed ARCore. Verify SLAM consumes glasses sensors during `Active` and phone sensors during `Fallback`. |
| `CalibrationFlowTest` | With scripted hit-test results and IMU motion, the calibration solve completes and produces a transform within tolerance. State transitions to `Active`. |

### 8.3 Manual

1. Pair Meta Ray-Ban via the rail item. Verify pairing overlay surfaces; complete pairing in the companion app.
2. Calibration: hold phone in off-hand pointed at the wall; tap the wall on phone screen; complete the motion sequence. Verify calibration succeeds and `Active` state is entered.
3. Paint a stroke. Verify it appears anchored to the wall both in the phone preview and in the glasses' display (via the glasses' SDK preview if available, or by holding the glasses near the camera and observing them).
4. Disconnect Meta glasses (unplug, walk out of range, or kill SDK). Verify auto-fallback within 2s; banner appears; phone-only AR continues.
5. Reconnect glasses; verify session can resume after re-pairing + recalibration.
6. Recalibrate via the banner action mid-session. Verify the transform updates without ending the session.
7. Five-minute exploratory session: paint multiple layers, switch modes, verify rail items stay coherent (Spec 1's `wearable.main` ID and state-driven label/color).

### 8.4 Deliberately not tested

- Calibration accuracy across users / lighting conditions. Acceptance is "feels well-anchored" — qualitative.
- Battery / thermal behavior.
- Throughput / frame-drop benchmarks. Plan execution may add a frame-drop counter as instrumentation if tracking degrades, but that's not a test.
- Xreal connection or data flow (out of scope).

## 9. Definition of done

- All unit tests in §8.1 pass.
- Both integration tests in §8.2 pass.
- All seven manual scenarios in §8.3 pass on a Meta Ray-Ban + Pixel/Samsung target.
- The `wearable.main` rail callback is no longer empty.
- `WearableManager` is referenced through `activeSensorSource` from `SlamManager`.
- `XrealGlassProvider` compiles against the new interface and returns empty flows + `UNKNOWN` intrinsics.
- A 5-minute exploratory session in glasses mode does not require a force-close to recover.

## 10. Rollback

The wearables changes land in one PR. Rollback is `git revert` of that PR. Behavior reverts to phone-only AR (the pre-glasses path). The new `SensorSource` abstraction is the only cross-cutting change in `core/common`; if rolled back, `SlamManager` reverts to direct phone-camera/IMU access. No on-disk format change. The `wearable.main` rail item ID rename happened in Spec 1; this spec doesn't re-rename.

## 11. Open questions

These resolve during plan execution:

1. Whether the Meta SDK exposes camera intrinsics. If not, the spec uses a hardcoded fallback table per known device model (Meta has a small product line). Acceptable approximation for tap calibration.
2. Whether the Meta SDK's camera callback returns YUV or RGBA and at what resolution. Determines the frame-format handling in `MetaGlassProvider`.
3. Whether the SLAM stack's existing `feedFrame` API tolerates being driven from a non-phone source's clock (timestamps in glasses time, not phone time). If clocks differ, `MetaGlassProvider` does the conversion using a single-shot offset measured at session start.
4. The exact ARCore hit-test API for the calibration tap (it's likely `Frame.hitTest(motionEvent)`; verify against the AR feature's existing usage).
5. Whether the Procrustes solve needs SVD or whether a direct closed-form rigid-transform solve is sufficient. For ~20 paired points it's the same; whichever is in `feature/ar`'s existing math utilities wins.
6. Whether ARCore and the SLAM stack can share the phone camera surface or if they need separate `Camera2` sessions. Existing project may have already solved this; if not, ARCore takes the camera, the SLAM stack consumes from the wearables provider only when in glasses mode.
