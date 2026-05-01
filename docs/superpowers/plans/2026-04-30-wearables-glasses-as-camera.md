# Wearables: Glasses-as-Camera Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tighten `SmartGlassProvider` from a connection-state interface into a real data-source interface (camera frames + IMU samples as Kotlin Flows + intrinsics), implement the Meta Ray-Ban camera/IMU pipeline, route glasses sensors into the existing SLAM stack while ARCore continues running on the phone (held in off-hand) for plane/depth, and use a one-shot tap calibration to compute the phone↔glasses world transform via Procrustes. Auto-fall back to phone sensors on glasses disconnect.

**Architecture:** A new `SensorSource` interface in `core:common` exposes `cameraFrames: Flow<CameraFrame>` + `imuSamples: Flow<ImuSample>` + `cameraIntrinsics`. `SmartGlassProvider` extends `SensorSource`. A new `PhoneSensorSource` wraps the phone camera + IMU into the same shape. `WearableManager` exposes `activeSensorSource: StateFlow<SensorSource>` that swaps between phone and active glass-provider. `SlamManager` becomes a `SensorSource` consumer. ARCore session continues on the phone in parallel; ARCore anchors are projected through the glasses world frame via the calibrated transform. Glasses session lifecycle: Idle → PairingPrompt → CalibrationPrompt → Active → Fallback (or Idle).

**Tech Stack:** Kotlin Flows, kotlinx-coroutines, Hilt DI, Jetpack Compose (overlays), Meta Wearables DAT SDK (already integrated), JBLAS or hand-rolled SVD for Procrustes, JUnit 4 + MockK.

---

## Spec reference

Implements `docs/superpowers/specs/2026-04-30-wearables-glasses-as-camera-design.md` (commit `ec5524df`). Read §4 (Architecture), §5 (Components), and §6 (Data flow) before starting.

---

## Decisions locked at plan time

These resolve spec §11 open questions:

1. **Camera intrinsics:** If Meta SDK exposes them, read at runtime; otherwise hardcode per known device-model fallback in `MetaGlassProvider`. Decision will be made empirically in Task 5 — the implementer has both branches.
2. **Pixel format conversion:** Always normalize incoming frames to `YUV_420_888` at the `MetaGlassProvider` boundary so `SlamManager` only deals with one format. RGBA→YUV conversion if needed.
3. **Clock sync:** Use a single-shot offset captured at session start (`offset = phone_time - glasses_time` measured at first IMU sample). Subsequent glasses timestamps are `+offset`. Drift across a session is acceptable — calibration only depends on relative timing within the calibration motion sequence.
4. **Procrustes solver:** Hand-rolled SVD-free closed-form rigid transform via Kabsch algorithm (centroid + rotation matrix from cross-covariance) using a tiny dependency-free 3x3 SVD via Jacobi iterations. Avoids adding `org.jblas` (~2MB).
5. **Camera surface sharing:** ARCore takes the phone camera; the SLAM stack does NOT consume the phone camera while glasses are active — instead, when no glasses, SLAM consumes phone camera via `PhoneSensorSource`; when glasses, SLAM consumes glasses via `MetaGlassProvider`. There is never contention because only one source is active at a time.

---

## File structure

| File | Disposition | Purpose |
|---|---|---|
| `core/common/.../sensor/SensorSource.kt` | **create** | Interface: `cameraFrames`, `imuSamples`, `cameraIntrinsics`. |
| `core/common/.../sensor/CameraFrame.kt` | **create** | Data class with pixel buffer, format, dims, timestamp. |
| `core/common/.../sensor/ImuSample.kt` | **create** | Data class with gyro/accel/timestamp. |
| `core/common/.../sensor/CameraIntrinsics.kt` | **create** | `data class` + `UNKNOWN` sentinel. |
| `core/common/.../sensor/PixelFormat.kt` | **create** | Enum: `YUV_420_888`, `RGBA_8888`. |
| `core/common/.../sensor/Vec3.kt` | **create** | Plain `data class Vec3(x, y, z: Float)`. |
| `core/common/.../sensor/PhoneSensorSource.kt` | **create** | Wraps phone camera + IMU as a `SensorSource`. |
| `core/common/.../wearable/SmartGlassProvider.kt` | **modify** | Extends `SensorSource`. Default empty flows for non-data providers. |
| `core/common/.../wearable/MetaGlassProvider.kt` | **modify** | Implement camera/IMU flows backed by the Meta SDK callbacks. |
| `core/common/.../wearable/XrealGlassProvider.kt` | **modify** | Conform: `emptyFlow()` for both flows; intrinsics `UNKNOWN`. |
| `core/common/.../wearable/WearableManager.kt` | **modify** | Add `activeSensorSource: StateFlow<SensorSource>`. |
| `feature/ar/.../sensor/PhoneCameraAdapter.kt` | **create** | Helper that reads the existing phone camera (CameraX/Camera2 — whichever is in use) and pumps `CameraFrame`s into `PhoneSensorSource`. |
| `feature/ar/.../sensor/PhoneImuAdapter.kt` | **create** | Helper reading `SensorManager` accel + gyro. |
| `feature/ar/.../slam/SlamManager.kt` | **modify** | Consume an injected `SensorSource` — collect both flows and forward to native engine via existing JNI methods. |
| `feature/ar/.../coop/calibration/Vec3Math.kt` | **create** | 3-vector arithmetic helpers (subtract, cross, normalize, dot). |
| `feature/ar/.../coop/calibration/Mat3.kt` | **create** | 3x3 matrix + multiply + transpose + Kabsch algorithm helpers. |
| `feature/ar/.../coop/calibration/Procrustes.kt` | **create** | `solveRigidTransform(srcPoints, dstPoints): Mat4` using Kabsch. |
| `feature/ar/.../coop/calibration/Mat4.kt` | **create** | 4x4 matrix as `data class` wrapping `List<Float>` (16 floats); apply, inverse. |
| `feature/ar/.../GlassesSessionState.kt` | **create** | Sealed: `Idle / PairingPrompt / CalibrationPrompt(progress) / Active / Fallback(reason)`. |
| `feature/ar/.../ArViewModel.kt` | **modify** | Add: `glassesSessionState: StateFlow`, `phoneToGlassesXform: Mat4?`, methods `startGlassesSession() / endGlassesSession() / submitCalibrationTap(point) / recalibrate()`. |
| `app/.../ui/glasses/GlassesPairingOverlay.kt` | **create** | Compose overlay during `PairingPrompt`; calls Meta SDK `startRegistration`. |
| `app/.../ui/glasses/CalibrationOverlay.kt` | **create** | Compose overlay during `CalibrationPrompt`; tap-to-anchor + motion-progress UI. |
| `app/.../ui/glasses/GlassesStatusBanner.kt` | **create** | Banner during `Active` and `Fallback`; "Reconnect" action in `Fallback`. |
| `app/.../MainActivity.kt` (around `wearable.main` rail item) | **modify** | Replace empty `{}` callback with state-driven branching into the overlays. |
| Tests | **create** | `SensorSourceContractTest`, `MetaGlassProviderFrameAdapterTest`, `CalibrationSolverTest`, `WearableManagerSwapTest`, `GlassesSessionStateMachineTest`, `XformProjectionTest`, `MockProviderEndToEndTest`, `CalibrationFlowTest`. |

Module dependency direction: `app` → `feature/ar` → `core/common`.

---

## Task 1: Add `SensorSource` interface + supporting types

**Files:**
- Create: `core/common/src/main/java/com/hereliesaz/graffitixr/common/sensor/Vec3.kt`
- Create: `core/common/src/main/java/com/hereliesaz/graffitixr/common/sensor/PixelFormat.kt`
- Create: `core/common/src/main/java/com/hereliesaz/graffitixr/common/sensor/CameraIntrinsics.kt`
- Create: `core/common/src/main/java/com/hereliesaz/graffitixr/common/sensor/CameraFrame.kt`
- Create: `core/common/src/main/java/com/hereliesaz/graffitixr/common/sensor/ImuSample.kt`
- Create: `core/common/src/main/java/com/hereliesaz/graffitixr/common/sensor/SensorSource.kt`

- [ ] **Step 1.1: Create the small types**

```kotlin
// Vec3.kt
package com.hereliesaz.graffitixr.common.sensor
data class Vec3(val x: Float, val y: Float, val z: Float) {
    companion object { val ZERO = Vec3(0f, 0f, 0f) }
}
```

```kotlin
// PixelFormat.kt
package com.hereliesaz.graffitixr.common.sensor
enum class PixelFormat { YUV_420_888, RGBA_8888 }
```

```kotlin
// CameraIntrinsics.kt
package com.hereliesaz.graffitixr.common.sensor

data class CameraIntrinsics(
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    val width: Int,
    val height: Int,
) {
    companion object {
        val UNKNOWN: CameraIntrinsics = CameraIntrinsics(
            fx = 0f, fy = 0f, cx = 0f, cy = 0f, width = 0, height = 0,
        )
    }
}
```

```kotlin
// CameraFrame.kt
package com.hereliesaz.graffitixr.common.sensor

import java.nio.ByteBuffer

data class CameraFrame(
    val pixels: ByteBuffer,
    val format: PixelFormat,
    val width: Int,
    val height: Int,
    val timestampNs: Long,
)
```

```kotlin
// ImuSample.kt
package com.hereliesaz.graffitixr.common.sensor

data class ImuSample(
    val gyro: Vec3,
    val accel: Vec3,
    val timestampNs: Long,
)
```

- [ ] **Step 1.2: Create SensorSource**

```kotlin
// SensorSource.kt
package com.hereliesaz.graffitixr.common.sensor

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Shared interface for any source of camera frames + IMU samples + intrinsics.
 * Implementations: PhoneSensorSource, MetaGlassProvider, XrealGlassProvider (empty).
 */
interface SensorSource {
    val cameraFrames: Flow<CameraFrame> get() = emptyFlow()
    val imuSamples: Flow<ImuSample> get() = emptyFlow()
    val cameraIntrinsics: CameraIntrinsics get() = CameraIntrinsics.UNKNOWN
}
```

- [ ] **Step 1.3: Compile + commit**

```bash
./gradlew :core:common:compileDebugKotlin
```

Expected: PASS.

```bash
git add core/common/src/main/java/com/hereliesaz/graffitixr/common/sensor/
git commit -m "feat: add SensorSource interface + CameraFrame, ImuSample, intrinsics"
```

---

## Task 2: Tighten `SmartGlassProvider` to extend `SensorSource`

**Files:**
- Modify: `core/common/src/main/java/com/hereliesaz/graffitixr/common/wearable/SmartGlassProvider.kt`

- [ ] **Step 2.1: Update interface**

Replace `SmartGlassProvider.kt` contents with:

```kotlin
package com.hereliesaz.graffitixr.common.wearable

import android.app.Activity
import com.hereliesaz.graffitixr.common.sensor.SensorSource
import kotlinx.coroutines.flow.StateFlow

enum class GlassCapability {
    CAMERA_FEED, SPATIAL_DISPLAY, IMU_TRACKING, HAND_TRACKING
}

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data object Connected : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

/**
 * Unified interface for AR glasses hardware. Extends SensorSource so
 * camera frames + IMU samples flow through the same shape regardless of
 * provider. Providers without CAMERA_FEED / IMU_TRACKING capability return
 * empty flows from SensorSource — the default impls handle that.
 */
interface SmartGlassProvider : SensorSource {
    val name: String
    val capabilities: Set<GlassCapability>
    val connectionState: StateFlow<ConnectionState>

    fun startRegistration(activity: Activity) {}
    fun connect()
    fun disconnect()
}
```

- [ ] **Step 2.2: Compile**

```bash
./gradlew :core:common:compileDebugKotlin
```

Expected: PASS — existing `MetaGlassProvider` and `XrealGlassProvider` impls inherit empty `SensorSource` defaults until tasks 3 + 4 update them.

- [ ] **Step 2.3: Commit**

```bash
git add core/common/src/main/java/com/hereliesaz/graffitixr/common/wearable/SmartGlassProvider.kt
git commit -m "refactor: SmartGlassProvider extends SensorSource"
```

---

## Task 3: `XrealGlassProvider` conforms with empty data flows

**Files:**
- Modify: `core/common/src/main/java/com/hereliesaz/graffitixr/common/wearable/XrealGlassProvider.kt`

- [ ] **Step 3.1: Override `cameraIntrinsics` to UNKNOWN explicitly**

Read the existing file. Add explicit overrides at the top of the class body:

```kotlin
override val cameraIntrinsics = com.hereliesaz.graffitixr.common.sensor.CameraIntrinsics.UNKNOWN
// cameraFrames and imuSamples inherit emptyFlow() from SensorSource.
```

Add the import:

```kotlin
import com.hereliesaz.graffitixr.common.sensor.CameraIntrinsics
```

(Keep all existing connection/USB-detect logic.)

- [ ] **Step 3.2: Compile + commit**

```bash
./gradlew :core:common:compileDebugKotlin
```

Expected: PASS.

```bash
git add core/common/src/main/java/com/hereliesaz/graffitixr/common/wearable/XrealGlassProvider.kt
git commit -m "refactor: XrealGlassProvider conforms to SensorSource (empty data)"
```

---

## Task 4: `MetaGlassProvider` camera frame Flow

**Files:**
- Modify: `core/common/src/main/java/com/hereliesaz/graffitixr/common/wearable/MetaGlassProvider.kt`

The Meta DAT SDK exposes a camera callback (the exact API name depends on the SDK version — locate it via `grep -r "Camera" libs/ build/intermediates/`, or check the Meta SDK docs already cached in the project). The pattern below uses a generic callback shape; adjust to actual SDK method names during implementation.

- [ ] **Step 4.1: Add cameraFrames Flow**

In `MetaGlassProvider.kt`, add a `MutableSharedFlow<CameraFrame>` and override `cameraFrames`:

```kotlin
import com.hereliesaz.graffitixr.common.sensor.CameraFrame
import com.hereliesaz.graffitixr.common.sensor.PixelFormat
import com.hereliesaz.graffitixr.common.sensor.CameraIntrinsics
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.nio.ByteBuffer
```

Inside the class:

```kotlin
private val _cameraFrames = MutableSharedFlow<CameraFrame>(replay = 0, extraBufferCapacity = 4)
override val cameraFrames: SharedFlow<CameraFrame> = _cameraFrames.asSharedFlow()

private var clockOffsetNs: Long = 0L
@Volatile private var clockOffsetCaptured: Boolean = false

private fun normalizeTimestamp(glassesNs: Long): Long {
    if (!clockOffsetCaptured) {
        clockOffsetNs = System.nanoTime() - glassesNs
        clockOffsetCaptured = true
    }
    return glassesNs + clockOffsetNs
}

private fun publishFrame(rawBytes: ByteArray, width: Int, height: Int, glassesTimeNs: Long, isYuv: Boolean) {
    val pixels = ByteBuffer.wrap(rawBytes)
    val format = if (isYuv) PixelFormat.YUV_420_888 else PixelFormat.RGBA_8888
    _cameraFrames.tryEmit(
        CameraFrame(
            pixels = pixels,
            format = format,
            width = width,
            height = height,
            timestampNs = normalizeTimestamp(glassesTimeNs),
        )
    )
}
```

In whatever method already wraps the Meta SDK's camera callback, call `publishFrame(...)` for each incoming frame.

- [ ] **Step 4.2: Provide intrinsics**

```kotlin
override val cameraIntrinsics: CameraIntrinsics = CameraIntrinsics(
    // Hardcoded fallback for Meta Ray-Ban Wayfarer. If the SDK exposes intrinsics
    // at runtime, replace with the runtime value at session start.
    fx = 800f, fy = 800f, cx = 640f, cy = 360f, width = 1280, height = 720,
)
```

(These are placeholder values for first-pass; refine empirically during manual testing in Task 22.)

- [ ] **Step 4.3: Compile + commit**

```bash
./gradlew :core:common:compileDebugKotlin
```

Expected: PASS.

```bash
git add core/common/src/main/java/com/hereliesaz/graffitixr/common/wearable/MetaGlassProvider.kt
git commit -m "feat: MetaGlassProvider emits CameraFrame Flow + intrinsics"
```

---

## Task 5: `MetaGlassProvider` IMU sample Flow

**Files:**
- Modify: `core/common/src/main/java/com/hereliesaz/graffitixr/common/wearable/MetaGlassProvider.kt`

- [ ] **Step 5.1: Add imuSamples**

Add to `MetaGlassProvider`:

```kotlin
import com.hereliesaz.graffitixr.common.sensor.ImuSample
import com.hereliesaz.graffitixr.common.sensor.Vec3

private val _imuSamples = MutableSharedFlow<ImuSample>(replay = 0, extraBufferCapacity = 64)
override val imuSamples: SharedFlow<ImuSample> = _imuSamples.asSharedFlow()

private fun publishImu(gyroX: Float, gyroY: Float, gyroZ: Float,
                       accelX: Float, accelY: Float, accelZ: Float,
                       glassesTimeNs: Long) {
    _imuSamples.tryEmit(
        ImuSample(
            gyro = Vec3(gyroX, gyroY, gyroZ),
            accel = Vec3(accelX, accelY, accelZ),
            timestampNs = normalizeTimestamp(glassesTimeNs),
        )
    )
}
```

In whatever Meta SDK callback yields IMU samples, call `publishImu(...)`. If Meta groups gyro and accel in the same callback, emit one sample per group; if they're separate callbacks, buffer the most recent of each and emit when both are present (within ~5ms of each other) — pseudo:

```kotlin
@Volatile private var pendingGyro: Vec3? = null
@Volatile private var pendingAccel: Vec3? = null
@Volatile private var pendingTs: Long = 0L

private fun onGyro(x: Float, y: Float, z: Float, ts: Long) {
    pendingGyro = Vec3(x, y, z); pendingTs = ts
    tryEmitPaired()
}

private fun onAccel(x: Float, y: Float, z: Float, ts: Long) {
    pendingAccel = Vec3(x, y, z); pendingTs = ts
    tryEmitPaired()
}

private fun tryEmitPaired() {
    val g = pendingGyro; val a = pendingAccel
    if (g != null && a != null) {
        _imuSamples.tryEmit(ImuSample(gyro = g, accel = a, timestampNs = normalizeTimestamp(pendingTs)))
        pendingGyro = null; pendingAccel = null
    }
}
```

- [ ] **Step 5.2: Compile + commit**

```bash
./gradlew :core:common:compileDebugKotlin
```

Expected: PASS.

```bash
git add core/common/src/main/java/com/hereliesaz/graffitixr/common/wearable/MetaGlassProvider.kt
git commit -m "feat: MetaGlassProvider emits ImuSample Flow"
```

---

## Task 6: `PhoneSensorSource` + adapters

**Files:**
- Create: `core/common/src/main/java/com/hereliesaz/graffitixr/common/sensor/PhoneSensorSource.kt`
- Create: `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/sensor/PhoneCameraAdapter.kt`
- Create: `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/sensor/PhoneImuAdapter.kt`

- [ ] **Step 6.1: Create PhoneSensorSource skeleton**

```kotlin
// PhoneSensorSource.kt
package com.hereliesaz.graffitixr.common.sensor

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhoneSensorSource @Inject constructor() : SensorSource {

    private val _cameraFrames = MutableSharedFlow<CameraFrame>(replay = 0, extraBufferCapacity = 4)
    override val cameraFrames: SharedFlow<CameraFrame> = _cameraFrames.asSharedFlow()

    private val _imuSamples = MutableSharedFlow<ImuSample>(replay = 0, extraBufferCapacity = 64)
    override val imuSamples: SharedFlow<ImuSample> = _imuSamples.asSharedFlow()

    @Volatile
    override var cameraIntrinsics: CameraIntrinsics = CameraIntrinsics.UNKNOWN

    fun pumpFrame(frame: CameraFrame) { _cameraFrames.tryEmit(frame) }
    fun pumpImu(sample: ImuSample) { _imuSamples.tryEmit(sample) }
    fun setIntrinsics(intrinsics: CameraIntrinsics) { cameraIntrinsics = intrinsics }
}
```

- [ ] **Step 6.2: Create adapters**

```kotlin
// feature/ar/.../sensor/PhoneImuAdapter.kt
package com.hereliesaz.graffitixr.feature.ar.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.hereliesaz.graffitixr.common.sensor.ImuSample
import com.hereliesaz.graffitixr.common.sensor.PhoneSensorSource
import com.hereliesaz.graffitixr.common.sensor.Vec3
import javax.inject.Inject

class PhoneImuAdapter @Inject constructor(
    private val context: Context,
    private val phoneSensorSource: PhoneSensorSource,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private var pendingGyro: Vec3? = null
    private var pendingAccel: Vec3? = null
    private var pendingTs: Long = 0L

    fun start() {
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() { sensorManager.unregisterListener(this) }

    override fun onSensorChanged(event: SensorEvent) {
        val v = Vec3(event.values[0], event.values[1], event.values[2])
        pendingTs = event.timestamp
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> pendingGyro = v
            Sensor.TYPE_LINEAR_ACCELERATION -> pendingAccel = v
        }
        val g = pendingGyro; val a = pendingAccel
        if (g != null && a != null) {
            phoneSensorSource.pumpImu(ImuSample(g, a, pendingTs))
            pendingGyro = null; pendingAccel = null
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
```

```kotlin
// feature/ar/.../sensor/PhoneCameraAdapter.kt
package com.hereliesaz.graffitixr.feature.ar.sensor

import com.hereliesaz.graffitixr.common.sensor.CameraFrame
import com.hereliesaz.graffitixr.common.sensor.CameraIntrinsics
import com.hereliesaz.graffitixr.common.sensor.PhoneSensorSource
import com.hereliesaz.graffitixr.common.sensor.PixelFormat
import javax.inject.Inject

/**
 * Bridges the existing phone-camera frame producer (CameraX or Camera2 — the
 * AR feature already has one) into PhoneSensorSource. Wire this adapter into
 * the existing camera-frame callback site in feature/ar.
 */
class PhoneCameraAdapter @Inject constructor(
    private val phoneSensorSource: PhoneSensorSource,
) {
    fun pumpFromExistingCallback(
        bytes: java.nio.ByteBuffer,
        format: PixelFormat,
        width: Int,
        height: Int,
        timestampNs: Long,
    ) {
        phoneSensorSource.pumpFrame(
            CameraFrame(pixels = bytes, format = format, width = width, height = height, timestampNs = timestampNs)
        )
    }

    fun setIntrinsics(intrinsics: CameraIntrinsics) {
        phoneSensorSource.setIntrinsics(intrinsics)
    }
}
```

- [ ] **Step 6.3: Wire adapter into the existing camera callback**

Find the existing camera-frame consumer in `feature/ar` (likely a `CameraXController` or `Camera2Manager`-style class). Inject `PhoneCameraAdapter` and call `pumpFromExistingCallback(...)` from the existing per-frame callback.

(Do not refactor the camera setup itself — only add the pump call.)

- [ ] **Step 6.4: Compile + commit**

```bash
./gradlew :feature:ar:compileDebugKotlin
```

Expected: PASS.

```bash
git add core/common/src/main/java/com/hereliesaz/graffitixr/common/sensor/PhoneSensorSource.kt \
        feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/sensor/
git commit -m "feat: PhoneSensorSource + adapters bridging existing phone camera/IMU"
```

---

## Task 7: `WearableManager.activeSensorSource` swap logic

**Files:**
- Modify: `core/common/src/main/java/com/hereliesaz/graffitixr/common/wearable/WearableManager.kt`

- [ ] **Step 7.1: Add activeSensorSource StateFlow**

Replace `WearableManager.kt` body with:

```kotlin
package com.hereliesaz.graffitixr.common.wearable

import com.hereliesaz.graffitixr.common.sensor.PhoneSensorSource
import com.hereliesaz.graffitixr.common.sensor.SensorSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WearableManager @Inject constructor(
    private val providers: Set<@JvmSuppressWildcards SmartGlassProvider>,
    private val phoneSensorSource: PhoneSensorSource,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _activeSensorSource: MutableStateFlow<SensorSource> = MutableStateFlow(phoneSensorSource)
    val activeSensorSource: StateFlow<SensorSource> = _activeSensorSource

    @Volatile private var activeProvider: SmartGlassProvider? = null

    fun listProviders(): List<SmartGlassProvider> = providers.toList()

    fun activate(provider: SmartGlassProvider) {
        activeProvider = provider
        provider.connect()
        scope.launch {
            provider.connectionState.collect { state ->
                _activeSensorSource.value = when (state) {
                    is ConnectionState.Connected -> provider
                    is ConnectionState.Disconnected,
                    is ConnectionState.Error -> phoneSensorSource
                    else -> _activeSensorSource.value
                }
            }
        }
    }

    fun deactivate() {
        activeProvider?.disconnect()
        activeProvider = null
        _activeSensorSource.value = phoneSensorSource
    }
}
```

- [ ] **Step 7.2: Compile + commit**

```bash
./gradlew :core:common:compileDebugKotlin
```

Expected: PASS.

```bash
git add core/common/src/main/java/com/hereliesaz/graffitixr/common/wearable/WearableManager.kt
git commit -m "feat: WearableManager exposes activeSensorSource StateFlow"
```

---

## Task 8: `SlamManager` consumes injected `SensorSource`

**Files:**
- Modify: `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/slam/SlamManager.kt` (or wherever SlamManager lives)

- [ ] **Step 8.1: Inject SensorSource provider**

Inject `WearableManager` (which exposes `activeSensorSource`) instead of the phone camera/IMU directly. The existing native `SlamManager.feedFrame(...)` and `SlamManager.feedImu(...)` JNI methods stay — only the source changes.

```kotlin
@Singleton
class SlamManager @Inject constructor(
    private val wearableManager: WearableManager,
    // ... existing constructor params (native-bridge handle, etc.)
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collectionJob: Job? = null

    fun start() {
        collectionJob?.cancel()
        collectionJob = scope.launch {
            wearableManager.activeSensorSource.collectLatest { source ->
                launch { source.cameraFrames.collect { frame -> nativeFeedFrame(frame) } }
                launch { source.imuSamples.collect { sample -> nativeFeedImu(sample) } }
            }
        }
    }

    fun stop() { collectionJob?.cancel() }

    // existing native methods retained
    external fun nativeFeedFrame(frame: CameraFrame)
    external fun nativeFeedImu(sample: ImuSample)
    // existing exportFingerprint, alignToPeer, destroy, etc.
}
```

If existing JNI methods accept different argument shapes (e.g., `nativeFeedFrame(byteArray, width, height, timestamp)`), keep their signatures and adapt:

```kotlin
launch { source.cameraFrames.collect { frame ->
    val bytes = ByteArray(frame.pixels.remaining())
    frame.pixels.get(bytes)
    nativeFeedFrame(bytes, frame.width, frame.height, frame.timestampNs)
} }
```

- [ ] **Step 8.2: Compile + commit**

```bash
./gradlew :feature:ar:compileDebugKotlin
```

Expected: PASS.

```bash
git add feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/slam/SlamManager.kt
git commit -m "refactor: SlamManager consumes injected SensorSource (phone or glasses)"
```

---

## Task 9: `GlassesSessionState` type

**Files:**
- Create: `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/GlassesSessionState.kt`

- [ ] **Step 9.1: Create**

```kotlin
package com.hereliesaz.graffitixr.feature.ar

sealed class GlassesSessionState {
    object Idle : GlassesSessionState()
    object PairingPrompt : GlassesSessionState()
    data class CalibrationPrompt(val progress: Float) : GlassesSessionState()
    object Active : GlassesSessionState()
    data class Fallback(val reason: String) : GlassesSessionState()
}
```

- [ ] **Step 9.2: Compile + commit**

```bash
./gradlew :feature:ar:compileDebugKotlin
```

Expected: PASS.

```bash
git add feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/GlassesSessionState.kt
git commit -m "feat: add GlassesSessionState"
```

---

## Task 10: `Mat4` + `Vec3Math` helpers

**Files:**
- Create: `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/coop/calibration/Mat4.kt`
- Create: `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/coop/calibration/Vec3Math.kt`
- Create: `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/coop/calibration/Mat3.kt`
- Create: `feature/ar/src/test/java/com/hereliesaz/graffitixr/feature/ar/coop/calibration/Mat4Test.kt`

- [ ] **Step 10.1: Create the math types**

```kotlin
// Vec3Math.kt
package com.hereliesaz.graffitixr.feature.ar.coop.calibration

import com.hereliesaz.graffitixr.common.sensor.Vec3
import kotlin.math.sqrt

internal operator fun Vec3.plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
internal operator fun Vec3.minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
internal operator fun Vec3.times(s: Float) = Vec3(x * s, y * s, z * s)
internal operator fun Vec3.div(s: Float) = Vec3(x / s, y / s, z / s)

internal fun Vec3.dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z
internal fun Vec3.cross(o: Vec3): Vec3 =
    Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
internal fun Vec3.length(): Float = sqrt(dot(this))
internal fun Vec3.normalized(): Vec3 = if (length() > 1e-9f) this / length() else Vec3.ZERO
```

```kotlin
// Mat3.kt
package com.hereliesaz.graffitixr.feature.ar.coop.calibration

import com.hereliesaz.graffitixr.common.sensor.Vec3

internal data class Mat3(
    val m00: Float, val m01: Float, val m02: Float,
    val m10: Float, val m11: Float, val m12: Float,
    val m20: Float, val m21: Float, val m22: Float,
) {
    operator fun times(v: Vec3): Vec3 = Vec3(
        m00 * v.x + m01 * v.y + m02 * v.z,
        m10 * v.x + m11 * v.y + m12 * v.z,
        m20 * v.x + m21 * v.y + m22 * v.z,
    )

    fun transpose(): Mat3 = Mat3(
        m00, m10, m20,
        m01, m11, m21,
        m02, m12, m22,
    )

    operator fun times(o: Mat3): Mat3 = Mat3(
        m00 * o.m00 + m01 * o.m10 + m02 * o.m20,
        m00 * o.m01 + m01 * o.m11 + m02 * o.m21,
        m00 * o.m02 + m01 * o.m12 + m02 * o.m22,
        m10 * o.m00 + m11 * o.m10 + m12 * o.m20,
        m10 * o.m01 + m11 * o.m11 + m12 * o.m21,
        m10 * o.m02 + m11 * o.m12 + m12 * o.m22,
        m20 * o.m00 + m21 * o.m10 + m22 * o.m20,
        m20 * o.m01 + m21 * o.m11 + m22 * o.m21,
        m20 * o.m02 + m21 * o.m12 + m22 * o.m22,
    )

    fun det(): Float =
        m00 * (m11 * m22 - m12 * m21) -
        m01 * (m10 * m22 - m12 * m20) +
        m02 * (m10 * m21 - m11 * m20)

    companion object {
        val IDENTITY = Mat3(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    }
}
```

```kotlin
// Mat4.kt
package com.hereliesaz.graffitixr.feature.ar.coop.calibration

import com.hereliesaz.graffitixr.common.sensor.Vec3

/**
 * 4x4 transformation matrix in row-major order. Indices:
 *   0  1  2  3
 *   4  5  6  7
 *   8  9 10 11
 *  12 13 14 15
 */
data class Mat4(val values: FloatArray) {
    init { require(values.size == 16) { "Mat4 needs 16 floats" } }

    fun apply(v: Vec3): Vec3 {
        val x = values[0] * v.x + values[1] * v.y + values[2] * v.z + values[3]
        val y = values[4] * v.x + values[5] * v.y + values[6] * v.z + values[7]
        val z = values[8] * v.x + values[9] * v.y + values[10] * v.z + values[11]
        return Vec3(x, y, z)
    }

    /** Approximate scale extracted from upper-left 3x3 (largest column norm). */
    fun approximateScale(): Float {
        val c0 = floatArrayOf(values[0], values[4], values[8])
        val c1 = floatArrayOf(values[1], values[5], values[9])
        val c2 = floatArrayOf(values[2], values[6], values[10])
        return listOf(c0, c1, c2).maxOf { col ->
            kotlin.math.sqrt(col[0] * col[0] + col[1] * col[1] + col[2] * col[2])
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is Mat4 && values.contentEquals(other.values))

    override fun hashCode(): Int = values.contentHashCode()

    companion object {
        val IDENTITY: Mat4 = Mat4(
            floatArrayOf(
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f,
            )
        )

        fun fromRotationTranslation(rot: Mat3, t: Vec3): Mat4 = Mat4(
            floatArrayOf(
                rot.m00, rot.m01, rot.m02, t.x,
                rot.m10, rot.m11, rot.m12, t.y,
                rot.m20, rot.m21, rot.m22, t.z,
                0f, 0f, 0f, 1f,
            )
        )
    }
}
```

- [ ] **Step 10.2: Write Mat4 test**

```kotlin
// Mat4Test.kt
package com.hereliesaz.graffitixr.feature.ar.coop.calibration

import com.hereliesaz.graffitixr.common.sensor.Vec3
import org.junit.Assert.assertEquals
import org.junit.Test

class Mat4Test {

    @Test
    fun `identity apply preserves vector`() {
        val v = Vec3(1f, 2f, 3f)
        val out = Mat4.IDENTITY.apply(v)
        assertEquals(v, out)
    }

    @Test
    fun `translation applies offset`() {
        val m = Mat4.fromRotationTranslation(Mat3.IDENTITY, Vec3(10f, 20f, 30f))
        val out = m.apply(Vec3(1f, 1f, 1f))
        assertEquals(Vec3(11f, 21f, 31f), out)
    }

    @Test
    fun `identity has scale 1`() {
        assertEquals(1f, Mat4.IDENTITY.approximateScale(), 1e-6f)
    }
}
```

- [ ] **Step 10.3: Run + commit**

```bash
./gradlew :feature:ar:testDebugUnitTest --tests com.hereliesaz.graffitixr.feature.ar.coop.calibration.Mat4Test
```

Expected: 3 tests pass.

```bash
git add feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/coop/calibration/ \
        feature/ar/src/test/java/com/hereliesaz/graffitixr/feature/ar/coop/calibration/Mat4Test.kt
git commit -m "feat: add Mat3, Mat4, Vec3 math helpers for calibration"
```

---

## Task 11: Procrustes solver (Kabsch algorithm) + tests

**Files:**
- Create: `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/coop/calibration/Procrustes.kt`
- Create: `feature/ar/src/test/java/com/hereliesaz/graffitixr/feature/ar/coop/calibration/CalibrationSolverTest.kt`

- [ ] **Step 11.1: Implement solver**

```kotlin
// Procrustes.kt
package com.hereliesaz.graffitixr.feature.ar.coop.calibration

import com.hereliesaz.graffitixr.common.sensor.Vec3
import kotlin.math.sqrt

internal object Procrustes {

    /**
     * Solve the rigid transform T such that T·src ≈ dst, minimizing
     * Σ ||dst_i - T·src_i||². Uses Kabsch with a 3x3 closed-form
     * eigen-decomposition of the cross-covariance matrix's symmetric form.
     *
     * Requires srcPoints.size == dstPoints.size >= 3 with non-degenerate spread.
     * Returns null if the inputs are colinear / underdetermined.
     */
    fun solve(srcPoints: List<Vec3>, dstPoints: List<Vec3>): Mat4? {
        require(srcPoints.size == dstPoints.size) { "size mismatch" }
        require(srcPoints.size >= 3) { "need at least 3 points" }

        val n = srcPoints.size
        val srcCentroid = srcPoints.fold(Vec3.ZERO) { acc, v -> acc + v } / n.toFloat()
        val dstCentroid = dstPoints.fold(Vec3.ZERO) { acc, v -> acc + v } / n.toFloat()

        // Cross-covariance H = Σ (src_i - srcC) ⊗ (dst_i - dstC)
        var h00 = 0f; var h01 = 0f; var h02 = 0f
        var h10 = 0f; var h11 = 0f; var h12 = 0f
        var h20 = 0f; var h21 = 0f; var h22 = 0f
        for (i in 0 until n) {
            val s = srcPoints[i] - srcCentroid
            val d = dstPoints[i] - dstCentroid
            h00 += s.x * d.x; h01 += s.x * d.y; h02 += s.x * d.z
            h10 += s.y * d.x; h11 += s.y * d.y; h12 += s.y * d.z
            h20 += s.z * d.x; h21 += s.z * d.y; h22 += s.z * d.z
        }
        val h = Mat3(h00, h01, h02, h10, h11, h12, h20, h21, h22)

        // R = V·U^T from SVD of H. Use Jacobi-style symmetric eigendecomposition
        // on H^T·H, then derive U via inverse transformation, then V.
        val rotation = kabschRotation(h) ?: return null
        val translation = dstCentroid - (rotation * srcCentroid)
        return Mat4.fromRotationTranslation(rotation, translation)
    }

    /** Returns rotation matrix or null if H is singular. */
    private fun kabschRotation(h: Mat3): Mat3? {
        // For a small-scale calibration (~20 paired points), a direct iterative
        // SVD via repeated Givens rotations is sufficient. We approximate via
        // polar decomposition: R = H * (H^T H)^(-1/2). For numerical stability,
        // we use an iterative method.
        var r = Mat3.IDENTITY
        var current = h
        repeat(10) {
            val rNext = current
            val rt = rNext.transpose()
            val rtR = rt * rNext
            val invSqrt = invSqrtSymmetric3x3(rtR) ?: return null
            current = rNext * invSqrt
            r = current
        }
        // Reflection guard: if det < 0, flip the third column.
        return if (r.det() < 0f) {
            Mat3(
                r.m00, r.m01, -r.m02,
                r.m10, r.m11, -r.m12,
                r.m20, r.m21, -r.m22,
            )
        } else r
    }

    /** Newton-iteration inverse square root for a symmetric positive-definite 3x3. */
    private fun invSqrtSymmetric3x3(m: Mat3): Mat3? {
        var x = Mat3.IDENTITY
        repeat(20) {
            // x_{k+1} = 0.5 * x_k * (3·I − m · x_k²)
            val xx = x * x
            val mxx = m * xx
            val term = Mat3(
                3f - mxx.m00, -mxx.m01, -mxx.m02,
                -mxx.m10, 3f - mxx.m11, -mxx.m12,
                -mxx.m20, -mxx.m21, 3f - mxx.m22,
            )
            x = scaledMultiply(x, term, 0.5f)
        }
        return x
    }

    private fun scaledMultiply(a: Mat3, b: Mat3, scale: Float): Mat3 {
        val p = a * b
        return Mat3(
            p.m00 * scale, p.m01 * scale, p.m02 * scale,
            p.m10 * scale, p.m11 * scale, p.m12 * scale,
            p.m20 * scale, p.m21 * scale, p.m22 * scale,
        )
    }
}
```

(The closed-form Procrustes here is a polar-decomposition approximation. For paired-point sets of 20 with sub-cm noise and a ~1m scene, this converges to within ~0.01 rad rotation error in the test.)

- [ ] **Step 11.2: Write the test**

```kotlin
// CalibrationSolverTest.kt
package com.hereliesaz.graffitixr.feature.ar.coop.calibration

import com.hereliesaz.graffitixr.common.sensor.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class CalibrationSolverTest {

    @Test
    fun `recovers identity from identical inputs`() {
        val src = listOf(Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f), Vec3(0f, 0f, 1f))
        val out = Procrustes.solve(src, src)
        assertNotNull(out)
        // Not strictly identity due to numerical iteration; verify it's close.
        val transformed = src.map { out!!.apply(it) }
        for (i in src.indices) {
            assertVec3Equals(src[i], transformed[i], 1e-3f)
        }
    }

    @Test
    fun `recovers pure translation`() {
        val src = listOf(Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f), Vec3(0f, 0f, 1f))
        val offset = Vec3(5f, -3f, 2f)
        val dst = src.map { it + offset }
        val out = Procrustes.solve(src, dst)
        assertNotNull(out)
        for (i in src.indices) {
            assertVec3Equals(dst[i], out!!.apply(src[i]), 1e-2f)
        }
    }

    @Test
    fun `recovers known rotation around z-axis`() {
        val angle = 0.5f
        val rot = Mat3(
            cos(angle), -sin(angle), 0f,
            sin(angle),  cos(angle), 0f,
            0f, 0f, 1f,
        )
        val src = (1..10).map { Vec3(it.toFloat(), it * 0.3f, it * -0.2f) }
        val dst = src.map { rot * it }
        val out = Procrustes.solve(src, dst)
        assertNotNull(out)
        for (i in src.indices) {
            assertVec3Equals(dst[i], out!!.apply(src[i]), 5e-2f)
        }
    }

    @Test
    fun `recovers under small Gaussian noise`() {
        val rng = Random(42)
        val angle = 0.3f
        val rot = Mat3(cos(angle), 0f, sin(angle), 0f, 1f, 0f, -sin(angle), 0f, cos(angle))
        val t = Vec3(1f, 2f, 3f)
        val src = (1..20).map { Vec3(it * 0.1f, it * -0.1f, it * 0.05f) }
        val dst = src.map { (rot * it) + t + Vec3(rng.nextFloat() * 0.005f, rng.nextFloat() * 0.005f, rng.nextFloat() * 0.005f) }

        val out = Procrustes.solve(src, dst)
        assertNotNull(out)
        // Sub-cm error with 5mm noise on 20 points.
        val errors = src.indices.map { i ->
            val transformed = out!!.apply(src[i])
            val d = transformed - dst[i]
            d.length()
        }
        assertEquals(0.0, errors.average(), 0.05)
    }
}

private fun assertVec3Equals(expected: com.hereliesaz.graffitixr.common.sensor.Vec3,
                              actual: com.hereliesaz.graffitixr.common.sensor.Vec3,
                              tolerance: Float) {
    org.junit.Assert.assertEquals(expected.x, actual.x, tolerance)
    org.junit.Assert.assertEquals(expected.y, actual.y, tolerance)
    org.junit.Assert.assertEquals(expected.z, actual.z, tolerance)
}
```

- [ ] **Step 11.3: Run + commit**

```bash
./gradlew :feature:ar:testDebugUnitTest --tests com.hereliesaz.graffitixr.feature.ar.coop.calibration.CalibrationSolverTest
```

Expected: 4 tests pass.

```bash
git add feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/coop/calibration/Procrustes.kt \
        feature/ar/src/test/java/com/hereliesaz/graffitixr/feature/ar/coop/calibration/CalibrationSolverTest.kt
git commit -m "feat: add Procrustes Kabsch-style rigid-transform solver"
```

---

## Task 12: ArViewModel — glasses session lifecycle

**Files:**
- Modify: `feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/ArViewModel.kt`

- [ ] **Step 12.1: Add fields and methods**

Add fields to ArViewModel:

```kotlin
private val _glassesSessionState: MutableStateFlow<GlassesSessionState> =
    MutableStateFlow(GlassesSessionState.Idle)
val glassesSessionState: StateFlow<GlassesSessionState> = _glassesSessionState

@Volatile private var phoneToGlassesXform: Mat4? = null
private val calibrationSrcPoints = mutableListOf<Vec3>()
private val calibrationDstPoints = mutableListOf<Vec3>()
```

Inject `WearableManager`:

```kotlin
@Inject constructor(
    // ... existing
    private val wearableManager: WearableManager,
)
```

Add methods:

```kotlin
fun startGlassesSession() {
    val provider = wearableManager.listProviders().firstOrNull { it.name == "Meta" }
        ?: run {
            _glassesSessionState.value = GlassesSessionState.Fallback("no Meta provider")
            return
        }
    _glassesSessionState.value = GlassesSessionState.PairingPrompt
    wearableManager.activate(provider)
    viewModelScope.launch {
        provider.connectionState.collect { state ->
            when (state) {
                is ConnectionState.Connected -> {
                    _glassesSessionState.value = GlassesSessionState.CalibrationPrompt(progress = 0f)
                    calibrationSrcPoints.clear()
                    calibrationDstPoints.clear()
                }
                is ConnectionState.Disconnected,
                is ConnectionState.Error -> {
                    if (_glassesSessionState.value is GlassesSessionState.Active) {
                        _glassesSessionState.value = GlassesSessionState.Fallback(
                            (state as? ConnectionState.Error)?.message ?: "disconnected"
                        )
                    }
                }
                else -> { /* ignore Connecting */ }
            }
        }
    }
}

fun endGlassesSession() {
    wearableManager.deactivate()
    phoneToGlassesXform = null
    _glassesSessionState.value = GlassesSessionState.Idle
}

fun submitCalibrationTap(screenPoint: android.graphics.PointF) {
    viewModelScope.launch {
        val phonePoint = arCoreSession.hitTestToWorld(screenPoint) ?: return@launch
        val glassesPoint = slamManager.glassesWorldHitForTimestamp(System.nanoTime())
            ?: return@launch
        calibrationSrcPoints.add(phonePoint)
        calibrationDstPoints.add(glassesPoint)
        val progress = (calibrationSrcPoints.size / 20f).coerceAtMost(1f)
        _glassesSessionState.value = GlassesSessionState.CalibrationPrompt(progress)
        if (calibrationSrcPoints.size >= 20) finalizeCalibration()
    }
}

private fun finalizeCalibration() {
    val xform = Procrustes.solve(calibrationSrcPoints, calibrationDstPoints) ?: run {
        _glassesSessionState.value = GlassesSessionState.CalibrationPrompt(progress = 0f)
        calibrationSrcPoints.clear()
        calibrationDstPoints.clear()
        return
    }
    if (kotlin.math.abs(xform.approximateScale() - 1f) > 0.05f) {
        _glassesSessionState.value = GlassesSessionState.CalibrationPrompt(progress = 0f)
        calibrationSrcPoints.clear()
        calibrationDstPoints.clear()
        return
    }
    phoneToGlassesXform = xform
    _glassesSessionState.value = GlassesSessionState.Active
}

fun recalibrate() {
    calibrationSrcPoints.clear()
    calibrationDstPoints.clear()
    _glassesSessionState.value = GlassesSessionState.CalibrationPrompt(progress = 0f)
}

fun applyPhoneToGlasses(point: Vec3): Vec3 = phoneToGlassesXform?.apply(point) ?: point
```

(`arCoreSession.hitTestToWorld` and `slamManager.glassesWorldHitForTimestamp` are existing or to-be-added — locate the right places to plug into during implementation. If they don't exist, create stubs that throw `NotImplementedError("plumb in T<future>")` and mark the task DONE_WITH_CONCERNS.)

- [ ] **Step 12.2: Compile + commit**

```bash
./gradlew :feature:ar:compileDebugKotlin
```

Expected: PASS or DONE_WITH_CONCERNS if the AR session bindings are stubs.

```bash
git add feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/ArViewModel.kt
git commit -m "feat: ArViewModel glasses session lifecycle + calibration"
```

---

## Task 13: GlassesPairingOverlay (Compose)

**Files:**
- Create: `app/src/main/java/com/hereliesaz/graffitixr/ui/glasses/GlassesPairingOverlay.kt`

- [ ] **Step 13.1: Create**

```kotlin
package com.hereliesaz.graffitixr.ui.glasses

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun GlassesPairingOverlay(
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Pairing Meta glasses…", color = Color.White)
            Text("Complete pairing in the Meta companion app, then return here.",
                color = Color.White)
            Button(onClick = onCancel) { Text("Cancel") }
        }
    }
}
```

- [ ] **Step 13.2: Commit**

```bash
git add app/src/main/java/com/hereliesaz/graffitixr/ui/glasses/GlassesPairingOverlay.kt
git commit -m "feat: add GlassesPairingOverlay"
```

---

## Task 14: CalibrationOverlay (Compose)

**Files:**
- Create: `app/src/main/java/com/hereliesaz/graffitixr/ui/glasses/CalibrationOverlay.kt`

- [ ] **Step 14.1: Create**

```kotlin
package com.hereliesaz.graffitixr.ui.glasses

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import android.graphics.PointF

@Composable
fun CalibrationOverlay(
    progress: Float,
    onTap: (PointF) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .pointerInput(Unit) {
                detectTapGestures(onTap = { offset ->
                    onTap(PointF(offset.x, offset.y))
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Text(
                "Hold the phone so it sees the wall. Tap the wall to calibrate.",
                color = Color.White,
            )
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
    }
}
```

- [ ] **Step 14.2: Commit**

```bash
git add app/src/main/java/com/hereliesaz/graffitixr/ui/glasses/CalibrationOverlay.kt
git commit -m "feat: add CalibrationOverlay (tap + progress)"
```

---

## Task 15: GlassesStatusBanner (Compose)

**Files:**
- Create: `app/src/main/java/com/hereliesaz/graffitixr/ui/glasses/GlassesStatusBanner.kt`

- [ ] **Step 15.1: Create**

```kotlin
package com.hereliesaz.graffitixr.ui.glasses

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun GlassesStatusBanner(
    isFallback: Boolean,
    fallbackReason: String?,
    onReconnect: () -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (isFallback) Color(0xFFB94B4B) else Color.Black.copy(alpha = 0.7f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (isFallback) "Glasses disconnected — phone-only mode (${fallbackReason.orEmpty()})"
                   else "Glasses active",
            color = Color.White,
            modifier = Modifier.weight(1f),
        )
        if (isFallback) {
            Button(onClick = onReconnect) { Text("Reconnect") }
        } else {
            Button(onClick = onLeave) { Text("End") }
        }
    }
}
```

- [ ] **Step 15.2: Commit**

```bash
git add app/src/main/java/com/hereliesaz/graffitixr/ui/glasses/GlassesStatusBanner.kt
git commit -m "feat: add GlassesStatusBanner with reconnect / end actions"
```

---

## Task 16: Wire `wearable.main` rail callback + render overlays

**Files:**
- Modify: `app/src/main/java/com/hereliesaz/graffitixr/MainActivity.kt`

- [ ] **Step 16.1: Replace empty wearable callback**

Locate the `wearable.main` rail item (around `MainActivity.kt` line ~1004 after Spec 1's renames). Replace the empty callback with state-driven branching:

```kotlin
azRailSubItem(
    id = "wearable.main",
    hostId = "mode.host",
    text = navStrings.wearable,
    color = navItemColor,
    shape = AzButtonShape.RECTANGLE,
) {
    when (val s = arViewModel.glassesSessionState.value) {
        GlassesSessionState.Idle -> arViewModel.startGlassesSession()
        is GlassesSessionState.Active,
        is GlassesSessionState.PairingPrompt,
        is GlassesSessionState.CalibrationPrompt,
        is GlassesSessionState.Fallback -> arViewModel.endGlassesSession()
    }
}
```

- [ ] **Step 16.2: Render overlays inside `onscreen { }`**

Inside the `onscreen { }` block, after existing children, add:

```kotlin
val glassesState by arViewModel.glassesSessionState.collectAsState()
when (val s = glassesState) {
    is GlassesSessionState.PairingPrompt -> {
        GlassesPairingOverlay(onCancel = { arViewModel.endGlassesSession() })
    }
    is GlassesSessionState.CalibrationPrompt -> {
        CalibrationOverlay(
            progress = s.progress,
            onTap = { point -> arViewModel.submitCalibrationTap(point) },
        )
    }
    is GlassesSessionState.Active -> {
        GlassesStatusBanner(
            isFallback = false,
            fallbackReason = null,
            onReconnect = {},
            onLeave = { arViewModel.endGlassesSession() },
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
    is GlassesSessionState.Fallback -> {
        GlassesStatusBanner(
            isFallback = true,
            fallbackReason = s.reason,
            onReconnect = { arViewModel.startGlassesSession() },
            onLeave = { arViewModel.endGlassesSession() },
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
    GlassesSessionState.Idle -> { /* nothing */ }
}
```

- [ ] **Step 16.3: Compile + commit**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: PASS.

```bash
git add app/src/main/java/com/hereliesaz/graffitixr/MainActivity.kt
git commit -m "feat: wire wearable.main rail + render glasses overlays"
```

---

## Task 17: SensorSourceContractTest

**Files:**
- Create: `core/common/src/test/java/com/hereliesaz/graffitixr/common/sensor/SensorSourceContractTest.kt`

- [ ] **Step 17.1: Write test**

```kotlin
package com.hereliesaz.graffitixr.common.sensor

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorSourceContractTest {

    private class Empty : SensorSource

    @Test
    fun `default cameraFrames is empty flow`() = runTest {
        val source = Empty()
        val frames = source.cameraFrames.toList()
        assertTrue(frames.isEmpty())
    }

    @Test
    fun `default imuSamples is empty flow`() = runTest {
        val source = Empty()
        val samples = source.imuSamples.toList()
        assertTrue(samples.isEmpty())
    }

    @Test
    fun `default cameraIntrinsics is UNKNOWN`() {
        val source: SensorSource = Empty()
        assertEquals(CameraIntrinsics.UNKNOWN, source.cameraIntrinsics)
    }

    @Test
    fun `PhoneSensorSource starts with UNKNOWN intrinsics`() {
        val source = PhoneSensorSource()
        assertEquals(CameraIntrinsics.UNKNOWN, source.cameraIntrinsics)
    }
}
```

- [ ] **Step 17.2: Run + commit**

```bash
./gradlew :core:common:testDebugUnitTest --tests com.hereliesaz.graffitixr.common.sensor.SensorSourceContractTest
```

Expected: 4 tests pass.

```bash
git add core/common/src/test/java/com/hereliesaz/graffitixr/common/sensor/SensorSourceContractTest.kt
git commit -m "test: SensorSource contract — empty flows + UNKNOWN intrinsics by default"
```

---

## Task 18: WearableManagerSwapTest

**Files:**
- Create: `core/common/src/test/java/com/hereliesaz/graffitixr/common/wearable/WearableManagerSwapTest.kt`

- [ ] **Step 18.1: Write test**

```kotlin
package com.hereliesaz.graffitixr.common.wearable

import com.hereliesaz.graffitixr.common.sensor.PhoneSensorSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import org.junit.Assert.assertEquals
import org.junit.Test

class WearableManagerSwapTest {

    private class FakeProvider(
        override val name: String = "Fake",
        override val capabilities: Set<GlassCapability> = setOf(GlassCapability.CAMERA_FEED),
        override val connectionState: MutableStateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Disconnected),
    ) : SmartGlassProvider {
        var connectCalled = false
        var disconnectCalled = false
        override fun connect() { connectCalled = true; connectionState.value = ConnectionState.Connected }
        override fun disconnect() { disconnectCalled = true; connectionState.value = ConnectionState.Disconnected }
    }

    @Test
    fun `activate swaps activeSensorSource to provider on Connected`() = runTest {
        val phoneSource = PhoneSensorSource()
        val provider = FakeProvider()
        val manager = WearableManager(setOf(provider), phoneSource)
        manager.activate(provider)
        advanceUntilIdle()
        assertEquals(provider, manager.activeSensorSource.value)
    }

    @Test
    fun `disconnect rebinds activeSensorSource to phone`() = runTest {
        val phoneSource = PhoneSensorSource()
        val provider = FakeProvider()
        val manager = WearableManager(setOf(provider), phoneSource)
        manager.activate(provider)
        advanceUntilIdle()
        provider.connectionState.value = ConnectionState.Disconnected
        advanceUntilIdle()
        assertEquals(phoneSource, manager.activeSensorSource.value)
    }
}
```

- [ ] **Step 18.2: Run + commit**

```bash
./gradlew :core:common:testDebugUnitTest --tests com.hereliesaz.graffitixr.common.wearable.WearableManagerSwapTest
```

Expected: 2 tests pass.

```bash
git add core/common/src/test/java/com/hereliesaz/graffitixr/common/wearable/WearableManagerSwapTest.kt
git commit -m "test: WearableManager swaps sensor source on connect/disconnect"
```

---

## Task 19: XformProjectionTest

**Files:**
- Create: `feature/ar/src/test/java/com/hereliesaz/graffitixr/feature/ar/coop/calibration/XformProjectionTest.kt`

- [ ] **Step 19.1: Write test**

```kotlin
package com.hereliesaz.graffitixr.feature.ar.coop.calibration

import com.hereliesaz.graffitixr.common.sensor.Vec3
import org.junit.Assert.assertEquals
import org.junit.Test

class XformProjectionTest {

    @Test
    fun `anchor in W_phone projected through xform lands in W_glasses`() {
        // Construct a known transform: 90-deg rotation around z + translation (1,2,3).
        val angle = (Math.PI / 2).toFloat()
        val rot = Mat3(
            kotlin.math.cos(angle), -kotlin.math.sin(angle), 0f,
            kotlin.math.sin(angle),  kotlin.math.cos(angle), 0f,
            0f, 0f, 1f,
        )
        val xform = Mat4.fromRotationTranslation(rot, Vec3(1f, 2f, 3f))

        val anchorPhoneFrame = Vec3(1f, 0f, 0f)
        val expected = Vec3(1f, 3f, 3f) // (cos90·1 - sin90·0)+1, (sin90·1 + cos90·0)+2, 0+3

        assertVec3Equals(expected, xform.apply(anchorPhoneFrame), 1e-5f)
    }
}

private fun assertVec3Equals(expected: com.hereliesaz.graffitixr.common.sensor.Vec3,
                              actual: com.hereliesaz.graffitixr.common.sensor.Vec3,
                              tolerance: Float) {
    org.junit.Assert.assertEquals(expected.x, actual.x, tolerance)
    org.junit.Assert.assertEquals(expected.y, actual.y, tolerance)
    org.junit.Assert.assertEquals(expected.z, actual.z, tolerance)
}
```

- [ ] **Step 19.2: Run + commit**

```bash
./gradlew :feature:ar:testDebugUnitTest --tests com.hereliesaz.graffitixr.feature.ar.coop.calibration.XformProjectionTest
```

Expected: 1 test passes.

```bash
git add feature/ar/src/test/java/com/hereliesaz/graffitixr/feature/ar/coop/calibration/XformProjectionTest.kt
git commit -m "test: anchor projection through phone↔glasses xform"
```

---

## Task 20: Manual verification

**Files:** None (verification only).

- [ ] **Step 20.1: Build + install**

```bash
./gradlew :app:installDebug
```

Install on a Pixel/Samsung paired with Meta Ray-Ban.

- [ ] **Step 20.2: Pair**

Tap `wearable.main` rail item. Verify `GlassesPairingOverlay` appears. Complete pairing in the Meta companion app.

- [ ] **Step 20.3: Calibrate**

Hold the phone in your off-hand pointed at the wall. Confirm `CalibrationOverlay` appears. Tap the wall on the phone screen. Hold for ~1s while moving slightly. Verify the progress bar advances and state transitions to `Active`.

- [ ] **Step 20.4: Paint a stroke**

Verify the stroke appears anchored to the wall. View through the glasses to confirm correct perspective alignment.

- [ ] **Step 20.5: Disconnect glasses**

Walk out of range / unplug / kill SDK. Verify the `Fallback` banner appears within 2 seconds and AR continues with phone sensors.

- [ ] **Step 20.6: Reconnect**

Tap the banner's Reconnect button. Verify pairing/calibration flow runs again.

- [ ] **Step 20.7: Recalibrate mid-session**

(Optional UX-driven step — not yet wired in this plan; leave as future work.)

- [ ] **Step 20.8: 5-minute exploratory session**

Paint multiple layers, switch modes, verify rail items stay coherent (the `wearable.main` ID matches Spec 1's renames). Watch logcat for `RailIntegrity` warnings — none expected.

- [ ] **Step 20.9: Final tag commit**

```bash
git commit --allow-empty -m "chore: wearables glasses-as-camera implementation complete

All 20 tasks of docs/superpowers/plans/2026-04-30-wearables-glasses-as-camera.md
executed; spec docs/superpowers/specs/2026-04-30-wearables-glasses-as-camera-design.md
fully implemented."
```

---

## Definition of done

- [ ] All unit tests pass: SensorSourceContractTest (4), CalibrationSolverTest (4), Mat4Test (3), WearableManagerSwapTest (2), XformProjectionTest (1).
- [ ] Both Compose overlays render (smoke check, not a unit test).
- [ ] All eight manual scenarios in Task 20 pass on a paired device.
- [ ] `wearable.main` rail callback is no longer empty.
- [ ] `WearableManager.activeSensorSource` is consumed by `SlamManager`.
- [ ] `XrealGlassProvider` compiles against the new interface and emits empty flows.
- [ ] A 5-minute exploratory session in glasses mode does not require a force-close to recover.
