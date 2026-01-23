cmake_minimum_required(VERSION 3.22.1)

project("graffitixr")

# --- OPTIMIZATIONS ---
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -std=c++17 -ffast-math -fopenmp")

# --- DEPENDENCY PATHS ---
# Use LIBS_DIR passed from Gradle, or fallback to relative path if testing manually
if(NOT DEFINED LIBS_DIR)
    get_filename_component(LIBS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../libs" ABSOLUTE)
endif()

message(STATUS "Native build looking for libs in: ${LIBS_DIR}")

# --- OPENCV CONFIGURATION ---
# FIX: Point strictly to 'sdk/native/jni'. Do NOT append ABI folders here.
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
add_library(
        graffitixr # Matches project name, usually good practice
        SHARED
        GraffitiJNI.cpp
        MobileGS.cpp
)

# --- INCLUDE DIRECTORIES ---
target_include_directories(
    graffitixr PRIVATE
    include
    ${OpenCV_INCLUDE_DIRS}
    ${LIBS_DIR}/glm # Use LIBS_DIR for GLM too
)

# --- SYSTEM LIBRARIES ---
find_library(log-lib log)
find_library(android-lib android)
find_library(gles3-lib GLESv3) # Use GLESv3 for better graphics support if available

# --- LINKING ---
target_link_libraries(
        graffitixr
        
        # External
        ${OpenCV_LIBS}      # This automatically links the correct native .so for the ABI
        OpenMP::OpenMP_CXX
        
        # System
        ${log-lib}
        ${android-lib}
        ${gles3-lib}
        jnigraphics         # Often needed for bitmap manipulation
        EGL
)
