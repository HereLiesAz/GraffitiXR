cmake_minimum_required(VERSION 3.22.1)

project("graffitixr")

# --- OPTIMIZATIONS ---
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -std=c++17 -ffast-math -fopenmp")

# --- DEPENDENCY PATHS ---
if(NOT DEFINED LIBS_DIR)
    get_filename_component(LIBS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../libs" ABSOLUTE)
endif()

message(STATUS "Native build looking for libs in: ${LIBS_DIR}")

# --- OPENCV CONFIGURATION ---
set(OpenCV_DIR "${LIBS_DIR}/opencv/sdk/native/jni")

if(EXISTS "${OpenCV_DIR}/OpenCVConfig.cmake")
    find_package(OpenCV REQUIRED)
    message(STATUS "OpenCV found: ${OpenCV_LIBS}")
else()
    message(FATAL_ERROR "OpenCVConfig.cmake not found at ${OpenCV_DIR}. Run setup_libs.sh")
endif()

# --- OPENMP ---
find_package(OpenMP REQUIRED)

# --- SOURCE FILES ---
# CRITICAL: Target name MUST match System.loadLibrary("graffitixr")
add_library(
        graffitixr
        SHARED
        GraffitiJNI.cpp
        MobileGS.cpp
)

# --- INCLUDE DIRECTORIES ---
target_include_directories(
    graffitixr PRIVATE
    include
    ${OpenCV_INCLUDE_DIRS}
    ${LIBS_DIR}/glm
)

# --- SYSTEM LIBRARIES ---
find_library(log-lib log)
find_library(android-lib android)
find_library(gles3-lib GLESv3)

# --- LINKING ---
target_link_libraries(
        graffitixr

        # External
        ${OpenCV_LIBS}
        OpenMP::OpenMP_CXX

        # System
        ${log-lib}
        ${android-lib}
        ${gles3-lib}
        jnigraphics
        EGL
)