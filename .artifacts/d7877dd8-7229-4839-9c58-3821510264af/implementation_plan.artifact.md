# Fix OpenCV Compilation Errors and Warnings

The project is currently failing to build because several OpenCV functions and constants are not found in the `cv` namespace, and some legacy initialization methods are deprecated. This plan addresses these issues by updating includes and modernizing matrix initialization.

## Proposed Changes

### [Component: Native Bridge]

Update the headers and source files to include the missing geometry definitions and use modern initialization.

#### [MODIFY] [MobileGS.h](file:///G:/My Drive/GraffitiXR/core/nativebridge/src/main/cpp/include/MobileGS.h)
- Add `#include <opencv2/geometry.hpp>` and `#include <opencv2/calib3d.hpp>` to ensure all 2D/3D geometry and calibration functions (like `solvePnPRansac`, `projectPoints`, etc.) are visible.

#### [MODIFY] [SurfaceMesh.cpp](file:///G:/My Drive/GraffitiXR/core/nativebridge/src/main/cpp/SurfaceMesh.cpp)
- Add `#include <opencv2/calib3d.hpp>` or `#include <opencv2/geometry.hpp>` to ensure `getPerspectiveTransform` is visible.

#### [MODIFY] [MobileGS.cpp](file:///G:/My Drive/GraffitiXR/core/nativebridge/src/main/cpp/MobileGS.cpp)
- Replace deprecated comma initializers (`operator<<`) with `std::initializer_list` constructors for `cv::Mat`.
- Ensure all calls to `solvePnPRansac`, `projectPoints`, etc., are resolved.

## Verification Plan

### Automated Tests
- Run Gradle task `:core:nativebridge:assembleDebug` (or equivalent) to verify compilation.
- I will specifically target `:core:nativebridge:buildCMakeRelWithDebInfo[arm64-v8a]` as seen in the error log.

### Manual Verification
- None required as these are build-time fixes.
