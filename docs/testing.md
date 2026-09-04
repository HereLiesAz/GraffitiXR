// FILE: docs/testing.md
# Testing Strategy

## 1. Unit Tests (Kotlin)

Unit tests live in `src/test/` inside each module. Run all at once or per-module:

~~~bash
./gradlew testDebugUnitTest
./gradlew :feature:ar:testDebugUnitTest
./gradlew :core:data:testDebugUnitTest
~~~

### Existing test files

| File | Module | Covers |
|---|---|---|
| `DualAnalyzerTest` | `:feature:ar` | Relocalization callback, light throttle, luminosity path |
| `ArViewModelTest` | `:feature:ar` | Session management, flashlight, GPS, keyframe capture, fingerprint restore on project load |
| `EditorViewModelTest` | `:feature:editor` | Design placement/legibility, undo/redo, replace-confirmation flow |
| `ProjectManagerTest` | `:core:data` | `getProjectList`, `deleteProject`, `getMapPath`, `importProjectFromUri` failure paths |
| `NativeMethodAritySignatureTest` | `:core:nativebridge` | Regex-scrapes `GraffitiJNI.cpp` to catch a Kotlin `external fun` whose parameter count drifts from its native counterpart (does not check types) |
| `SlamManagerAnchorEstablishmentTest` | `:core:nativebridge` | Pins named historical regressions in anchor-establishment sequencing |
| `FingerprintJniContractTest` | `:core:common` | Guards the frozen `Fingerprint.fromNative` JNI constructor contract |

*Note: The relocalization and confidence/progress logic itself — `MobileGS::runRelocPass`
(background PnP snap-back), the distortion-head crop, `MobileGS::tryUpdateFingerprint`'s fallback —
runs entirely in the C++ layer and has **no automated coverage of any kind**, JVM or native. The
tests above guard the JNI *boundary* (signatures, contracts, sequencing) — they do not verify the
relocalization algorithm itself. See §2 below for the only verification that currently exists for
that code.*

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
No automated C++ test runner is integrated, and there is no on-device visual debug pipeline for it
either — there is no 3D map or surface-normal visualization to inspect (see `NATIVE_ENGINE.md`). The
only coverage of native code is the JVM-side JNI-boundary tests in the table above, plus the "Wall
Test" in §4 below. This is a real gap: the relocalization algorithm's actual correctness (match
quality, PnP accuracy, drift-correction behaviour) is currently unverified by any repeatable test —
see `docs/research/EVALUATION.md` for the state of that effort.

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