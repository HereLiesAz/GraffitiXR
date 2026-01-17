cmake_minimum_required(VERSION 3.18.1)

project("graffiti-lib")

# --- OPTIMIZATIONS ---
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -std=c++17 -ffast-math -fopenmp")

# --- OPENCV CONFIGURATION ---
# FIX: Pointing explicitly to the user's ABI-specific OpenCV config path.
# Path logic: ${CMAKE_SOURCE_DIR} is 'app/src/main/cpp'
# ../../../ takes us to 'app'
# Then into 'libs/opencv/...'
set(OpenCV_DIR "${CMAKE_SOURCE_DIR}/../../../libs/opencv/sdk/native/jni/abi-arm64-v8a")

message(STATUS "Forcing OpenCV_DIR to: ${OpenCV_DIR}")

find_package(OpenCV REQUIRED)

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
)