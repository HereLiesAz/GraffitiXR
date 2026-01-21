cmake_minimum_required(VERSION 3.22.1)

project("graffiti-lib")

# --- OPTIMIZATIONS ---
# Note: -fopenmp requires linking against the OpenMP library in Android
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -std=c++17 -ffast-math -fopenmp")

# --- OPENCV CONFIGURATION ---
get_filename_component(OPENCV_SDK_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/opencv/sdk/native/jni" ABSOLUTE)
set(OpenCV_DIR "${OPENCV_SDK_DIR}/abi-${ANDROID_ABI}")

find_package(OpenCV REQUIRED)
find_package(OpenMP REQUIRED)

# --- SOURCE FILES ---
add_library(
        graffiti-lib
        SHARED
        GraffitiJNI.cpp
        MobileGS.cpp
)

# --- INCLUDE DIRECTORIES ---
target_include_directories(
    graffiti-lib PRIVATE
    include
    ${OpenCV_INCLUDE_DIRS}
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/glm
)

# --- LINKING ---
find_library(log-lib log)
find_library(android-lib android)
find_library(gles3-lib GLESv3)

target_link_libraries(
        graffiti-lib
        ${OpenCV_LIBS}
        ${log-lib}
        ${android-lib}
        ${gles3-lib}
        OpenMP::OpenMP_CXX
)
