# FILE: core/nativebridge/src/main/cpp/CMakeLists.txt
cmake_minimum_required(VERSION 3.22.1)

project("graffitixr_native")

# OpenCV is imported from Maven Central (org.opencv:opencv). Since 4.9.0 that artifact ships a
# Prefab part, so with buildFeatures { prefab = true } in build.gradle.kts, AGP generates a CMake
# config package for it.
find_package(OpenCV REQUIRED CONFIG)

# Set GLM include directory (header-only, committed under libs/glm)
set(GLM_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/glm)

add_library(graffitixr SHARED
    GraffitiJNI.cpp
    MobileGS.cpp
    HomographyTracker.cpp
    SuperPointDetector.cpp
    DistortionHead.cpp
    LowLightEnhancer.cpp
    MlasStub.cpp
)

# 16 KB page-size support. Android's newer devices (and Play's requirement) use a 16 KB memory page
# size; a shared library laid out for 4 KB pages fails to load there. Force 16 KB ELF segment
# alignment explicitly so libgraffitixr.so is correct regardless of the NDK's default (older NDKs
# default to 4 KB). Prebuilt deps (ONNX Runtime, OpenCV) ship their own 16 KB-aligned .so.
target_link_options(graffitixr PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)

target_include_directories(graffitixr PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${GLM_DIR}
)

find_library(log-lib log)

target_link_libraries(graffitixr
    ${log-lib}
    OpenCV::opencv_java5
    GLESv3
    EGL
    android
    jnigraphics
)
