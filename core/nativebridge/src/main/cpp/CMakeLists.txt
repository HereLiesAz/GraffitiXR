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
    HomographyTracker.cpp
    SuperPointDetector.cpp
    DistortionHead.cpp
    LowLightEnhancer.cpp
    MlasStub.cpp
    KpmBridge.cpp
)

# ---------------------------------------------------------------------------
# Forked artoolkitX KPM (non-ARCore tracking base). Vendored as a git submodule at
# third_party/artoolkitx. Built ONLY when the submodule is checked out; otherwise KpmBridge.cpp
# compiles without HAVE_ARX_KPM and its JNI calls are safe no-ops, so the app and CI build unchanged.
#
# FIRST-CUT / BRING-UP: this static-lib target globs KPM + its FreakMatcher matcher plus the AR-param,
# ARUtil and OCVT support KPM needs, and links OpenCV. It is expected to need iteration on the first
# on-device NDK build (missing sources, defines, or OpenCV API deltas) — that is the point of the
# -PposeProbe smoke test. Repo-root/third_party/artoolkitx/Source/ARX is five levels up from here.
set(ARX_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third_party/artoolkitx/Source/ARX)
if(EXISTS ${ARX_ROOT}/KPM/include/ARX/KPM/kpm.h)
    message(STATUS "graffitixr: building forked artoolkitX KPM from ${ARX_ROOT}")
    find_library(log-lib log)

    file(GLOB_RECURSE ARX_KPM_SRC
        ${ARX_ROOT}/KPM/*.cpp
        ${ARX_ROOT}/OCVT/*.cpp
    )
    file(GLOB ARX_SUPPORT_SRC
        ${ARX_ROOT}/AR/*.c
        ${ARX_ROOT}/ARUtil/*.c
    )

    add_library(arx_kpm STATIC ${ARX_KPM_SRC} ${ARX_SUPPORT_SRC})
    target_compile_definitions(arx_kpm PUBLIC ARX_TARGET_PLATFORM_ANDROID HAVE_OPENCV)
    target_include_directories(arx_kpm PUBLIC
        ${ARX_ROOT}/include
        ${ARX_ROOT}/KPM/include
        ${ARX_ROOT}/KPM
        ${ARX_ROOT}/KPM/FreakMatcher
        ${ARX_ROOT}/AR/include
        ${ARX_ROOT}/ARUtil/include
        ${ARX_ROOT}/OCVT/include
    )
    target_link_libraries(arx_kpm OpenCV::opencv_java5 ${log-lib})

    target_link_libraries(graffitixr arx_kpm)
    target_compile_definitions(graffitixr PRIVATE HAVE_ARX_KPM)
    target_include_directories(graffitixr PRIVATE ${ARX_ROOT}/include ${ARX_ROOT}/KPM/include)
else()
    message(STATUS "graffitixr: artoolkitX submodule not present; KPM disabled (KpmBridge is a no-op)")
endif()

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
