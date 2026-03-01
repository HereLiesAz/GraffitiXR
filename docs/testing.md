# Testing Strategy

## 1. Unit Tests (Kotlin)

Unit tests live in `src/test/` inside each module. Run all at once or per-module:

```bash
./gradlew testDebugUnitTest
./gradlew :feature:ar:testDebugUnitTest
./gradlew :core:data:testDebugUnitTest
```

### Existing test files

| File | Module | Covers |
|---|---|---|
| `TeleologicalTrackerTest` | `:feature:ar` | `trackAndCorrect` PnP result handling, `Mat.release()` |
| `DualAnalyzerTest` | `:feature:ar` | SLAM callback, light throttle, luminosity path |
| `ArViewModelTest` | `:feature:ar` | Session management, flashlight, GPS, keyframe capture |
| `EditorViewModelTest` | `:feature:editor` | Layer operations, bitmap dimensions, undo/redo |
| `ProjectManagerTest` | `:core:data` | `getProjectList`, `deleteProject`, `getMapPath`, `importProjectFromUri` failure paths |

### Mock patterns

**Android Log on JVM** — throws `RuntimeException` unless mocked:
```kotlin
mockkStatic(Log::class)
every { Log.e(any(), any()) } returns 0
every { Log.e(any(), any(), any()) } returns 0
every { Log.i(any(), any()) } returns 0
```

**Kotlin objects** (singletons):
```kotlin
mockkObject(ImageProcessingUtils)
mockkObject(BitmapUtils)
coEvery { BitmapUtils.getBitmapDimensions(any()) } returns Pair(100, 100)
```

**OpenCV `Mat`** — `Mat()` calls native code; instantiating it on JVM causes `UnsatisfiedLinkError`:
```kotlin
val mat = mockk<Mat>(relaxed = true)
every { mat.get(any<Int>(), any<Int>()) } returns doubleArrayOf(1.0)
```

**ARCore `Session`** — cannot be instantiated on JVM. ARCore session tests belong in instrumented (`src/androidTest/`) tests, not JVM unit tests.

**CameraManager** (flashlight):
```kotlin
val cameraManager = mockk<CameraManager>(relaxed = true)
every { context.getSystemService(Context.CAMERA_SERVICE) } returns cameraManager
```

## 2. Native Tests (C++)
No automated C++ test runner is integrated. Use visual verification:
*   Enable `DEBUG_COLORS` in `MobileGS.h`.
*   Scan a corner. If the corner looks like a rainbow, the normals are wrong.

## 3. UI Tests (Compose)
*   **Location:** `src/androidTest/` in each module.
*   **Scope:** `AzNavRail` interactions.
*   **Note:** Mock `ArRenderer` when writing Compose tests — ARCore cannot be instantiated in UI tests.

## 4. Field Testing (The "Wall Test")
Before a release:
1.  Build release APK.
2.  Go to a physical brick wall.
3.  Scan it — confirm `TRACKING` chip turns green in the AR viewport.
4.  Project an image.
5.  Walk 5 metres away and return.
6.  **Pass Condition:** The image is still on the wall within < 1cm of drift.
