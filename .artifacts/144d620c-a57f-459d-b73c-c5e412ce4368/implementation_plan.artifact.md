# Implementation Plan - Fix Build Failures

The project is currently failing to build due to a Kotlin compilation error in `:core:common` and a native build error (Ninja) in `:opencv`.

## User Review Required

> [!IMPORTANT]
> The project is located on a Google Drive synced folder (`G:\My Drive\GraffitiXR`). This is causing the C++ build system (Ninja) to fail with `manifest 'build.ninja' still dirty` because of file timestamp inconsistencies common with cloud-sync providers.
>
> **Recommended Action:** If possible, move the project to a local, non-synced directory (e.g., `C:\Projects\GraffitiXR`) to avoid persistent build issues with native code.

## Proposed Changes

### [core:common]

Fix the unresolved reference to `getPerspectiveTransform`. In the version of OpenCV used in this project, this function is located in `org.opencv.geometry.Geometry` rather than the standard `org.opencv.imgproc.Imgproc`.

#### [MODIFY] [ImageProcessor.kt](file:///G:/My%20Drive/GraffitiXR/core/common/src/main/java/com/hereliesaz/graffitixr/common/util/ImageProcessor.kt)
- Update the call to `getPerspectiveTransform` to use the `Geometry` class.

### [opencv]

Address the Ninja "still dirty" error by performing a clean of the native build.

#### [ACTION] Clean Build
- Run `./gradlew :opencv:clean` to remove stale build artifacts and reset the Ninja manifest.

## Verification Plan

### Automated Tests
- Run `:core:common:compileReleaseKotlin` to verify the Kotlin fix.
- Run `:opencv:assembleRelease` to verify the native build fix.
- Run a full `:app:assembleDebug` or `:app:assembleRelease` to ensure the entire project builds.

### Manual Verification
- Verify that the app starts and the image processing features (if accessible) work as expected.
