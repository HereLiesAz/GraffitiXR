# Host (x86-64 Linux) unit tests for core/nativebridge's native sources.
#
# The Android build links OpenCV 5 from Maven prefab and runs on-device only; nothing native was
# executable in CI (BACKLOG.md Phase 7). This target compiles the SAME production .cpp files
# against the host's OpenCV 4 and GoogleTest, with a few one-file shims (shim/) standing in for
# Android/GL/JNI headers those sources include but the tested paths never call.
#
#   tools/run_native_host_tests.sh      configure + build + run (what CI calls)
#
# OpenCV 4 vs 5: only APIs present in both are exercised here; if production code starts using an
# OpenCV-5-only API this build fails loudly rather than testing something different.
cmake_minimum_required(VERSION 3.16)
project(graffitixr_native_host_tests CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(OpenCV REQUIRED COMPONENTS core imgproc features2d calib3d dnn)
find_package(GTest REQUIRED)
find_package(Threads REQUIRED)

set(SRC ${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp)
set(GLM_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/glm)

add_executable(native_host_tests
    HomographyTrackerTest.cpp
    MobileGSFingerprintStateTest.cpp
    FrameBufferGuardTest.cpp
    ${SRC}/HomographyTracker.cpp
    ${SRC}/MobileGS.cpp
    ${SRC}/SuperPointDetector.cpp
    ${SRC}/DistortionHead.cpp
    ${SRC}/LowLightEnhancer.cpp
)
# shim/ first so its headers win over (absent) Android/GL ones; then production headers.
target_include_directories(native_host_tests PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/shim
    ${SRC}/include
    ${SRC}
    ${GLM_DIR}
    ${OpenCV_INCLUDE_DIRS}
)
target_link_libraries(native_host_tests PRIVATE ${OpenCV_LIBS} GTest::gtest GTest::gtest_main Threads::Threads)

enable_testing()
include(GoogleTest)
gtest_discover_tests(native_host_tests)
