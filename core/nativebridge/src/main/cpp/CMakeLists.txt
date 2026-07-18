# FILE: core/nativebridge/src/main/cpp/CMakeLists.txt
cmake_minimum_required(VERSION 3.22.1)

project("graffitixr_native")

# OpenCV is imported from Maven Central (org.opencv:opencv). Since 4.9.0 that artifact ships a
# Prefab part, so with `buildFeatures { prefab = true }` in build.gradle.kts, AGP generates a CMake
# config package for it — nothing to vendor, fetch, or point OpenCV_DIR at. The package name is
# `OpenCV` and it exposes the imported target `OpenCV::opencv_java5` (headers + libopencv_java5.so).
find_package(OpenCV REQUIRED CONFIG)

# Set GLM include directory (header-only, committed under libs/glm)
set(GLM_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/glm)

# Define the native library (Removed VulkanBackend.cpp)
add_library(graffitixr SHARED
    GraffitiJNI.cpp
    MobileGS.cpp
    StereoProcessor.cpp
    SuperPointDetector.cpp
    DistortionHead.cpp
    LowLightEnhancer.cpp
    ImageWarper.cpp
    MlasStub.cpp
)

# Include directories. OpenCV's headers arrive via the imported target's INTERFACE_INCLUDE_DIRECTORIES.
target_include_directories(graffitixr PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${GLM_DIR}
)

# Find required Android libraries
find_library(log-lib log)

# Link libraries - OpenGL ES 3.0 for splat rendering, jnigraphics for Bitmap manipulation.
# OpenCV::opencv_java5 carries both the include dirs and the shared library to link against.
target_link_libraries(graffitixr
    ${log-lib}
    OpenCV::opencv_java5
    GLESv3
    EGL
    android
    jnigraphics
)
