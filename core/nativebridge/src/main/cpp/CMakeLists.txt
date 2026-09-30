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
    KpmBridge.cpp
)

# ---------------------------------------------------------------------------
# SphereSLAM / artoolkitX KPM.
#
# This is a SIDE-BY-SIDE native tracker. ARCore remains independent in feature/ar.
# KPM is compiled only when the pinned artoolkitX submodule is checked out. Without it, KpmBridge
# builds safe unavailable stubs and the rest of GraffitiXR is unchanged.
set(ARX_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third_party/artoolkitx/Source/ARX)
set(ARX_SOURCE_ROOT ${ARX_ROOT}/..)

if(EXISTS ${ARX_ROOT}/KPM/include/ARX/KPM/kpm.h)
    message(STATUS "graffitixr: building SphereSLAM KPM from ${ARX_ROOT}")

    find_library(log-lib log)
    find_library(m-lib m)

    # artoolkitX normally generates this header from its top-level CMake. We build only the KPM/AR
    # subset, so generate the same header locally with the pinned source version and the features this
    # subset actually uses.
    set(ARX_VERSION_MAJOR 1)
    set(ARX_VERSION_MINOR 1)
    set(ARX_VERSION_TINY 23)
    set(ARX_VERSION_DEV 0)
    set(HAVE_NFT 1)
    set(HAVE_2D 0)
    set(HAVE_GL 0)
    set(HAVE_GLES2 0)
    set(HAVE_GL3 0)
    set(HAVE_OPENCV 0)
    set(HAVE_GTSAM 0)
    set(USE_CPARAM_SEARCH 0)
    set(USE_GL_STATE_CACHE 0)
    set(USE_GTSAM 0)
    set(HAVE_INTEL_SIMD 0)

    set(ARX_GENERATED_INCLUDE ${CMAKE_CURRENT_BINARY_DIR}/arx_generated)
    file(MAKE_DIRECTORY ${ARX_GENERATED_INCLUDE}/ARX/AR)
    configure_file(
        ${ARX_ROOT}/AR/include/ARX/AR/config.h.in
        ${ARX_GENERATED_INCLUDE}/ARX/AR/config.h
        @ONLY
    )

    # The pinned artoolkitX submodule carries its Android JPEG headers and ABI-specific static lib.
    # KPM's matcher includes jpeglib even though our runtime path consumes raw luma frames.
    set(ARX_JPEG_INCLUDE ${ARX_SOURCE_ROOT}/depends/android/include)
    set(ARX_JPEG_LIB ${ARX_SOURCE_ROOT}/depends/android/lib/${ANDROID_ABI}/libjpeg.a)
    if(NOT EXISTS ${ARX_JPEG_LIB})
        message(FATAL_ERROR "graffitixr: artoolkitX JPEG library missing for ABI ${ANDROID_ABI}: ${ARX_JPEG_LIB}")
    endif()

    # KPM owns its FREAK matcher under this tree. The AR C sources provide the matrix/ICP and camera
    # parameter symbols KPM references; ARUtil/log.c supplies ARLOG*. OCVT is intentionally excluded.
    file(GLOB_RECURSE ARX_KPM_SRC CONFIGURE_DEPENDS
        ${ARX_ROOT}/KPM/*.cpp
        ${ARX_ROOT}/KPM/*.c
    )
    file(GLOB_RECURSE ARX_AR_C_SRC CONFIGURE_DEPENDS
        ${ARX_ROOT}/AR/*.c
    )
    set(ARX_SUPPORT_SRC
        ${ARX_ROOT}/ARUtil/log.c
    )

    add_library(arx_kpm STATIC
        ${ARX_KPM_SRC}
        ${ARX_AR_C_SRC}
        ${ARX_SUPPORT_SRC}
    )

    target_compile_definitions(arx_kpm PRIVATE
        ARX_TARGET_PLATFORM_ANDROID=1
    )
    target_include_directories(arx_kpm PUBLIC
        ${ARX_GENERATED_INCLUDE}
        ${ARX_ROOT}/KPM/include
        ${ARX_ROOT}/KPM
        ${ARX_ROOT}/KPM/FreakMatcher
        ${ARX_ROOT}/AR/include
        ${ARX_ROOT}/ARUtil/include
        ${ARX_JPEG_INCLUDE}
    )
    target_link_libraries(arx_kpm PRIVATE
        ${ARX_JPEG_LIB}
        ${log-lib}
        ${m-lib}
    )

    target_link_libraries(graffitixr arx_kpm)
    target_compile_definitions(graffitixr PRIVATE HAVE_ARX_KPM)
    target_include_directories(graffitixr PRIVATE
        ${ARX_GENERATED_INCLUDE}
        ${ARX_ROOT}/KPM/include
        ${ARX_ROOT}/AR/include
        ${ARX_ROOT}/ARUtil/include
    )
else()
    message(STATUS "graffitixr: artoolkitX submodule not present; SphereSLAM KPM disabled")
endif()

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
