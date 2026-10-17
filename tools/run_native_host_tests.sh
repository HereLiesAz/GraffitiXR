#!/usr/bin/env bash
# Build and run core/nativebridge's host-side native unit tests (see
# core/nativebridge/src/test/cpp/CMakeLists.txt). Needs a C++17 compiler, CMake >= 3.16, OpenCV 4
# development files and GoogleTest; on Ubuntu: apt-get install cmake g++ libopencv-dev libgtest-dev.
#
# The Android build links OpenCV 5. Set OPENCV_DIR to a directory holding OpenCVConfig.cmake for an
# OpenCV 5 build (e.g. <prefix>/lib/cmake/opencv5) to build and run the tests against it, so an
# OpenCV 5 API difference fails here instead of only in CI.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="${NATIVE_HOST_TEST_BUILD_DIR:-$ROOT/core/nativebridge/build/native-host-tests}"
CMAKE_ARGS=(-DCMAKE_BUILD_TYPE=Debug)
if [ -n "${OPENCV_DIR:-}" ]; then CMAKE_ARGS+=("-DOpenCV_DIR=$OPENCV_DIR"); fi
cmake -S "$ROOT/core/nativebridge/src/test/cpp" -B "$BUILD" "${CMAKE_ARGS[@]}"
cmake --build "$BUILD" -j "$(nproc 2>/dev/null || echo 2)"
"$BUILD/native_host_tests" "$@"
