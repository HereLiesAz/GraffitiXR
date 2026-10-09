// FILE: docs/testing.md
# Testing Strategy

## 1. Unit Tests (Kotlin)

Unit tests live in `src/test/` inside each module. Run all at once or per-module:

~~~bash
./gradlew testDebugUnitTest
./gradlew :feature:ar:testDebugUnitTest
./gradlew :core:data:testDebugUnitTest
~~~

### Test organization

Tests live under each module's own `src/test/` directory, mirroring the module boundaries described in
[`docs/ARCHITECTURE.md`](ARCHITECTURE.md) — e.g. `:feature:ar` view-model and analyzer tests,
`:feature:editor` design-placement tests, `:core:data` persistence tests, `:core:nativebridge` JNI
signature/contract tests, `:core:common` model/serialization tests. As of this writing there are over
100 `*Test.kt` files across the repository — too many to hand-enumerate here without the list going
stale the next time a test is added or renamed. For the current, authoritative inventory, run:

~~~bash
find . -path "*/src/test/*" -name "*Test.kt"
~~~

A few tests are worth calling out individually because they guard specific, easy-to-regress contracts
rather than ordinary feature behavior:

* `NativeMethodAritySignatureTest` (`:core:nativebridge`) — regex-scrapes `GraffitiJNI.cpp` to catch a
  Kotlin `external fun` whose parameter count drifts from its native counterpart (does not check types).
* `SlamManagerAnchorEstablishmentTest` (`:core:nativebridge`) — pins named historical regressions in
  anchor-establishment sequencing.
* `FingerprintJniContractTest` (`:core:common`) — guards the frozen `Fingerprint.fromNative` JNI
  constructor contract.

*Note: The relocalization and confidence/progress logic itself — `MobileGS::runRelocPass`
(background PnP snap-back), the distortion-head crop, `MobileGS::tryUpdateFingerprint`'s fallback —
runs entirely in the C++ layer and has no automated coverage of its *algorithmic* correctness. The
tests above guard the JNI *boundary* (signatures, contracts, sequencing); §2's host-native tests
cover the fallback tracker's pose math and fingerprint-replacement state, not reloc accuracy.*

### Mock patterns

**Android Log on JVM** — throws `RuntimeException` unless mocked:
~~~kotlin
mockkStatic(Log::class)
every { Log.e(any(), any()) } returns 0
every { Log.e(any(), any(), any()) } returns 0
every { Log.i(any(), any()) } returns 0
~~~

**Kotlin objects** (singletons):
~~~kotlin
mockkObject(ImageUtils)
coEvery { ImageUtils.loadBitmapAsync(any(), any(), any()) } returns testBitmap
~~~
(Neither `ImageProcessingUtils` nor `BitmapUtils` exist in the current codebase — `ImageUtils` is
the surviving bitmap-decode object.)

**OpenCV `Mat`** — `Mat()` calls native code; instantiating it on JVM causes `UnsatisfiedLinkError`:
~~~kotlin
val mat = mockk<Mat>(relaxed = true)
every { mat.get(any<Int>(), any<Int>()) } returns doubleArrayOf(1.0)
~~~

**ARCore `Session`** — cannot be instantiated on JVM. ARCore session tests belong in instrumented (`src/androidTest/`) tests, not JVM unit tests.

**CameraManager** (flashlight):
~~~kotlin
val cameraManager = mockk<CameraManager>(relaxed = true)
every { context.getSystemService(Context.CAMERA_SERVICE) } returns cameraManager
~~~

## 2. Native Tests (C++)
**Host-native GoogleTest suite** — `core/nativebridge/src/test/cpp/`, run with
`tools/run_native_host_tests.sh` (CI: Android CI ▸ unit-tests ▸ "Native host unit tests"). It
compiles the SAME production sources (`HomographyTracker.cpp`, `MobileGS.cpp`, the ONNX wrappers)
for x86-64 against host OpenCV 4 + GoogleTest, with one-file shims in `shim/` for Android/GL/JNI
headers the tested paths never call. Local setup (Ubuntu): `apt-get install cmake g++ libopencv-dev
libgtest-dev`.

| Test | Pins |
|---|---|
| `HomographyTrackerTest` | Known-answer pose: frames synthesised from a chosen GL pose via the GL pinhole model written out longhand; the tracker must recover it. Mutation-checked: re-introducing the old `C·R·C` flip fails both pose tests. |
| `MobileGSFingerprintStateTest` | `restoreWallFingerprint` / `alignToFingerprint` clear the previous fingerprint's capture view, anchor, intrinsics (and, for the restore, the canonical patch); malformed peer bytes change nothing. Private state via the `MobileGSTestPeer` friend. Mutation-checked. |
| `FrameBufferGuardTest` | `include/FrameBufferGuard.h`, the bounds arithmetic of `nativeFeedYuvFrame`, `nativeFeedColorFrame`, `nativeYuvToRgbaBitmap`, incl. 32-bit wrap. |

**OpenCV 4 vs 5:** the device links OpenCV 5; the host suite builds against 4. Only APIs present in
both compile — if production code adopts a 5-only API the host build fails loudly rather than
testing something else (`shim/opencv2/geometry.hpp` covers the one header 5 split out).

Still uncovered: reloc match quality, PnP accuracy and drift-correction behaviour — see
`docs/research/EVALUATION.md`. There is no on-device visual debug pipeline either (see
`NATIVE_ENGINE.md`).

**Robolectric** — `:core:data` (persistence) and `:core:common` (`CameraIntrinsicsEstimatorCameraIdTest`:
the Camera2 id → `CameraManager` → intrinsics seam every CameraX tracking path uses) run framework
classes the stub `android.jar` cannot.

## 3. UI / Instrumented Tests
There are currently **no `src/androidTest/` directories anywhere in this repository** — no
instrumented Compose tests, no on-device `AzNavRail` interaction tests. Every "verify with an
instrumented test" note elsewhere in this repo's docs or code comments describes a gap, not
something that exists yet.

## 4. Field Testing (The "Wall Test")
Before a release:
1.  Build release APK.
2.  Go to a physical brick wall.
3.  Scan it — confirm `TRACKING` chip turns green in the AR viewport.
4.  Project an image.
5.  Walk 5 metres away and return.
6.  **Pass Condition:** The image is still on the wall within < 1cm of drift.

---
*Documentation updated on 2026-09-04: removed the `DEBUG_COLORS`/surface-normal native verification
procedure (no such flag or renderer exists) and the `src/androidTest/` UI-test claim (no such
directories exist), added the three real JNI-boundary tests to the file table, and corrected the
relocalization test-coverage note. No prior dated footer.*