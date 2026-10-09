#!/usr/bin/env bash
# Build and run core/nativebridge's host-side native unit tests (see
# core/nativebridge/src/test/cpp/CMakeLists.txt). Needs a C++17 compiler, CMake >= 3.16, OpenCV 4
# development files and GoogleTest; on Ubuntu: apt-get install cmake g++ libopencv-dev libgtest-dev.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="${NATIVE_HOST_TEST_BUILD_DIR:-$ROOT/core/nativebridge/build/native-host-tests}"
cmake -S "$ROOT/core/nativebridge/src/test/cpp" -B "$BUILD" -DCMAKE_BUILD_TYPE=Debug
cmake --build "$BUILD" -j "$(nproc 2>/dev/null || echo 2)"
"$BUILD/native_host_tests" "$@"
