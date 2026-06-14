# Implementation Plan - Fix Native Build (Ninja) and OpenCV References

The build is currently failing with a `ninja: error: manifest 'build.ninja' still dirty after 100 tries` loop. This is a common issue when building native code on synced drives like Google Drive. We will also address the unresolved OpenCV references in `:core:common`.

## User Review Required

> [!IMPORTANT]
> **Google Drive Conflict:** The error `manifest 'build.ninja' still dirty` is almost certainly caused by Google Drive's sync mechanism touching files during the build process, which confuses Ninja's timestamp-based dependency tracking.
>
> **Recommendation:** While the steps below attempt a workaround, the most reliable fix is to **move the project to a local, non-synced directory** (e.g., `C:\GraffitiXR`).

## Proposed Changes

### [opencv] `:opencv`

We will restrict the build to ARM architectures to match `:core:nativebridge` and ensure a consistent CMake version is used. This reduces the build surface and avoids failing on `x86`/`x86_64` if those environments are causing issues.

#### [MODIFY] [build.gradle](file:///G:/My%20Drive/GraffitiXR/core/nativebridge/libs/opencv/sdk/build.gradle)
- Add `abiFilters 'arm64-v8a', 'armeabi-v7a'` to `defaultConfig`.
- Set `version "3.22.1"` in the `externalNativeBuild.cmake` block.

### [core:common] `:core:common`

Fix the unresolved reference to `getPerspectiveTransform` by using the correct class for this OpenCV version.

#### [MODIFY] [ImageProcessor.kt](file:///G:/My%20Drive/GraffitiXR/core/common/src/main/java/com/hereliesaz/graffitixr/common/util/ImageProcessor.kt)
- Ensure it uses `org.opencv.geometry.Geometry.getPerspectiveTransform` as intended in the current OpenCV 5.0.0 SDK structure.

### [Build System]

#### [ACTION] Deep Clean
- Manually delete the `.cxx` directories in both `:opencv` and `:core:nativebridge` to force a clean Ninja state.
- Run `./gradlew clean`.

## Verification Plan

### Automated Tests
- Run `./gradlew :opencv:assembleRelease` to verify the native build succeeds.
- Run `./gradlew :core:common:compileReleaseKotlin` to verify the Kotlin fix.
- Run `./gradlew :app:assembleDebug` to verify the full application build.

### Manual Verification
- Deploy the app to a device and verify that image unwarping works correctly.
