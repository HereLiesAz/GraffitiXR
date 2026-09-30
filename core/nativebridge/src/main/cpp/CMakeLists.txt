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

    # Match artoolkitX's own KPM CMake source list exactly. Do not recursively glob FreakMatcher:
    # that tree contains optional database-IO translation units that upstream itself does not build.
    set(ARX_KPM_SRC
        ${ARX_ROOT}/KPM/kpmHandle.cpp
        ${ARX_ROOT}/KPM/kpmRefDataSet.cpp
        ${ARX_ROOT}/KPM/kpmMatching.cpp
        ${ARX_ROOT}/KPM/kpmResult.cpp
        ${ARX_ROOT}/KPM/kpmUtil.cpp
        ${ARX_ROOT}/KPM/kpmFopen.c
        ${ARX_ROOT}/KPM/FreakMatcher/detectors/DoG_scale_invariant_detector.cpp
        ${ARX_ROOT}/KPM/FreakMatcher/detectors/gaussian_scale_space_pyramid.cpp
        ${ARX_ROOT}/KPM/FreakMatcher/detectors/gradients.cpp
        ${ARX_ROOT}/KPM/FreakMatcher/detectors/harris.cpp
        ${ARX_ROOT}/KPM/FreakMatcher/detectors/orientation_assignment.cpp
        ${ARX_ROOT}/KPM/FreakMatcher/detectors/pyramid.cpp
        ${ARX_ROOT}/KPM/FreakMatcher/facade/visual_database_facade.cpp
        ${ARX_ROOT}/KPM/FreakMatcher/matchers/hough_similarity_voting.cpp
        ${ARX_ROOT}/KPM/FreakMatcher/matchers/freak.cpp
        ${ARX_ROOT}/KPM/FreakMatcher/framework/date_time.cpp
        ${ARX_ROOT}/KPM/FreakMatcher/framework/image.cpp
        ${ARX_ROOT}/KPM/FreakMatcher/framework/logger.cpp
        ${ARX_ROOT}/KPM/FreakMatcher/framework/timers.cpp
    )
    # Match upstream AR/CMakeLists.txt instead of recursively compiling every .c file in AR.
    # The explicit list is boring on purpose: native build surprises are more boring.
    set(ARX_AR_SRC
        ${ARX_ROOT}/AR/ar3DCreateHandle.c
        ${ARX_ROOT}/AR/ar3DUtil.c
        ${ARX_ROOT}/AR/arCreateHandle.c
        ${ARX_ROOT}/AR/arDetectMarker.c
        ${ARX_ROOT}/AR/arDetectMarker2.c
        ${ARX_ROOT}/AR/arFilterTransMat.c
        ${ARX_ROOT}/AR/arGetLine.c
        ${ARX_ROOT}/AR/arGetMarkerInfo.c
        ${ARX_ROOT}/AR/arGetTransMat.c
        ${ARX_ROOT}/AR/arGetTransMatStereo.c
        ${ARX_ROOT}/AR/arImageProc.c
        ${ARX_ROOT}/AR/arLabeling.c
        ${ARX_ROOT}/AR/arLabelingSub/arLabelingSubDBIC.c
        ${ARX_ROOT}/AR/arLabelingSub/arLabelingSubDBRC.c
        ${ARX_ROOT}/AR/arLabelingSub/arLabelingSubDBZ.c
        ${ARX_ROOT}/AR/arLabelingSub/arLabelingSubDWIC.c
        ${ARX_ROOT}/AR/arLabelingSub/arLabelingSubDWRC.c
        ${ARX_ROOT}/AR/arLabelingSub/arLabelingSubDWZ.c
        ${ARX_ROOT}/AR/arLabelingSub/arLabelingSubEBIC.c
        ${ARX_ROOT}/AR/arLabelingSub/arLabelingSubEBRC.c
        ${ARX_ROOT}/AR/arLabelingSub/arLabelingSubEBZ.c
        ${ARX_ROOT}/AR/arLabelingSub/arLabelingSubEWIC.c
        ${ARX_ROOT}/AR/arLabelingSub/arLabelingSubEWRC.c
        ${ARX_ROOT}/AR/arLabelingSub/arLabelingSubEWZ.c
        ${ARX_ROOT}/AR/arMultiEditConfig.c
        ${ARX_ROOT}/AR/arMultiFreeConfig.c
        ${ARX_ROOT}/AR/arMultiGetTransMat.c
        ${ARX_ROOT}/AR/arMultiGetTransMatStereo.c
        ${ARX_ROOT}/AR/arMultiReadConfigFile.c
        ${ARX_ROOT}/AR/arPattAttach.c
        ${ARX_ROOT}/AR/arPattCreateHandle.c
        ${ARX_ROOT}/AR/arPattGetID.c
        ${ARX_ROOT}/AR/arPattLoad.c
        ${ARX_ROOT}/AR/arPattSave.c
        ${ARX_ROOT}/AR/arRefineCorners.cpp
        ${ARX_ROOT}/AR/arUtil.c
        ${ARX_ROOT}/AR/icpCalibStereo.c
        ${ARX_ROOT}/AR/icpCore.c
        ${ARX_ROOT}/AR/icpHandle.c
        ${ARX_ROOT}/AR/icpPoint.c
        ${ARX_ROOT}/AR/icpPointRobust.c
        ${ARX_ROOT}/AR/icpStereoHandle.c
        ${ARX_ROOT}/AR/icpStereoPoint.c
        ${ARX_ROOT}/AR/icpStereoPointRobust.c
        ${ARX_ROOT}/AR/icpUtil.c
        ${ARX_ROOT}/AR/mAlloc.c
        ${ARX_ROOT}/AR/mAllocDup.c
        ${ARX_ROOT}/AR/mAllocInv.c
        ${ARX_ROOT}/AR/mAllocMul.c
        ${ARX_ROOT}/AR/mAllocTrans.c
        ${ARX_ROOT}/AR/mAllocUnit.c
        ${ARX_ROOT}/AR/mDet.c
        ${ARX_ROOT}/AR/mDisp.c
        ${ARX_ROOT}/AR/mDup.c
        ${ARX_ROOT}/AR/mFree.c
        ${ARX_ROOT}/AR/mInv.c
        ${ARX_ROOT}/AR/mMul.c
        ${ARX_ROOT}/AR/mPCA.c
        ${ARX_ROOT}/AR/mSelfInv.c
        ${ARX_ROOT}/AR/mTrans.c
        ${ARX_ROOT}/AR/mUnit.c
        ${ARX_ROOT}/AR/paramChangeSize.c
        ${ARX_ROOT}/AR/paramClear.c
        ${ARX_ROOT}/AR/paramDecomp.c
        ${ARX_ROOT}/AR/paramDisp.c
        ${ARX_ROOT}/AR/paramDistortion.c
        ${ARX_ROOT}/AR/paramFile.c
        ${ARX_ROOT}/AR/paramGetPerspective.c
        ${ARX_ROOT}/AR/paramGL.c
        ${ARX_ROOT}/AR/paramLT.c
        ${ARX_ROOT}/AR/vAlloc.c
        ${ARX_ROOT}/AR/vDisp.c
        ${ARX_ROOT}/AR/vFree.c
        ${ARX_ROOT}/AR/vHouse.c
        ${ARX_ROOT}/AR/vInnerP.c
        ${ARX_ROOT}/AR/vTridiag.c
        ${ARX_ROOT}/AR/matrixCode.c
    )
    set(ARX_SUPPORT_SRC
        ${ARX_ROOT}/ARUtil/log.c
    )

    add_library(arx_kpm STATIC
        ${ARX_KPM_SRC}
        ${ARX_AR_SRC}
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
