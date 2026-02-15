cmake_minimum_required(VERSION 3.22.1)

project("graffitixr")

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Define paths relative to this CMakeLists.txt
set(NATIVE_BRIDGE_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../..")
set(OPENCV_ROOT_PATH "${NATIVE_BRIDGE_ROOT}/libs/opencv/sdk/native")
set(GLM_INCLUDE_DIR "${NATIVE_BRIDGE_ROOT}/libs/glm")

# Include GLM
include_directories(${GLM_INCLUDE_DIR})

# Include OpenCV
set(OpenCV_DIR ${OPENCV_ROOT_PATH}/jni)
find_package(OpenCV REQUIRED)

# Include directories for our own headers
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/include)
include_directories(${CMAKE_CURRENT_SOURCE_DIR})

# Define the Native Bridge library
add_library(graffitixr SHARED
        GraffitiJNI.cpp
        MobileGS.cpp
        StereoProcessor.cpp
        VulkanBackend.cpp)

# Find Android system libraries
find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)
find_library(vulkan-lib vulkan)
find_library(egl-lib EGL)
find_library(gles3-lib GLESv3)

# --- Manual Link 3rd Party Dependencies for Static OpenCV ---
macro(find_opencv_3rdparty name)
    find_library(LIB_${name}
        NAMES ${name}
        PATHS "${OPENCV_ROOT_PATH}/3rdparty/libs/${ANDROID_ABI}"
        NO_CMAKE_FIND_ROOT_PATH)

    if(LIB_${name})
        list(APPEND OPENCV_3RDPARTY_LIBS ${LIB_${name}})
    endif()
endmacro()

# Link all static dependencies explicitly
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
        ${OpenCV_LIBS}
        ${OPENCV_3RDPARTY_LIBS}
        ${log-lib}
        ${android-lib}
        ${jnigraphics-lib}
        ${vulkan-lib}
        ${egl-lib}
        ${gles3-lib})