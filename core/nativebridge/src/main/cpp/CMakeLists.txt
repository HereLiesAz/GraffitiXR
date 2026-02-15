cmake_minimum_required(VERSION 3.22.1)

project("graffitixr")

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Define the OpenCV SDK path relative to this file
# Assuming structure: core/nativebridge/src/main/cpp/CMakeLists.txt
# SDK at: core/nativebridge/libs/opencv/sdk
set(OPENCV_ROOT_PATH "${CMAKE_SOURCE_DIR}/../../../libs/opencv/sdk/native")

# Include OpenCV
# We use STATIC libs to avoid managing the separate libopencv_java4.so loader
set(OpenCV_DIR ${OPENCV_ROOT_PATH}/jni)
find_package(OpenCV REQUIRED)

# Define the Native Bridge library
add_library(graffitixr SHARED
        GraffitiJNI.cpp
        MobileGS.cpp
        VulkanBackend.cpp)

# Find Log and Android libs
find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics) # Bitmap access
find_library(vulkan-lib vulkan) # Vulkan support
find_library(egl-lib EGL) # EGL support for GL context management

# --- FIX: Manually Link 3rd Party Dependencies for Static OpenCV ---
# Static OpenCV requires linking all its internal dependencies explicitly if not handled by the config.
# KleidiCV is the critical missing piece causing linker errors on ARM64.

# Helper to find 3rdparty libs
macro(find_opencv_3rdparty name)
    find_library(LIB_${name}
        NAMES ${name}
        PATHS "${OPENCV_ROOT_PATH}/3rdparty/libs/${ANDROID_ABI}"
        NO_CMAKE_FIND_ROOT_PATH)

    if(LIB_${name})
        list(APPEND OPENCV_3RDPARTY_LIBS ${LIB_${name}})
    endif()
endmacro()

# Search for common OpenCV dependencies including the missing KleidiCV
# The error `undefined symbol: kleidicv::...` means this is REQUIRED.
find_opencv_3rdparty(kleidicv)
find_opencv_3rdparty(cpufeatures)
find_opencv_3rdparty(tegra_hal)
find_opencv_3rdparty(ittnotify)
find_opencv_3rdparty(ade)
find_opencv_3rdparty(libjpeg-turbo)
find_opencv_3rdparty(libpng)
find_opencv_3rdparty(libtiff)
find_opencv_3rdparty(libwebp)
find_opencv_3rdparty(libopenjp2)
find_opencv_3rdparty(IlmImf)
find_opencv_3rdparty(quirc)
find_opencv_3rdparty(tbb)
find_opencv_3rdparty(libprotobuf)

# Link everything
target_link_libraries(graffitixr
        ${OpenCV_LIBS}           # Main OpenCV modules (core, imgproc, etc.)
        ${OPENCV_3RDPARTY_LIBS}  # The missing static dependencies (KleidiCV, etc.)
        ${log-lib}
        ${android-lib}
        ${jnigraphics-lib}
        ${vulkan-lib}
        ${egl-lib})