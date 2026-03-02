cmake_minimum_required(VERSION 3.22.1)

project("graffitixr_native")

# Set the OpenCV directory.
set(OPENCV_SDK_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/opencv/sdk/native/jni/)
set(OpenCV_DIR ${OPENCV_SDK_DIR})

# Find OpenCV package
find_package(OpenCV REQUIRED)

# Set GLM include directory
set(GLM_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/glm)

# Define the native library
add_library(graffitixr SHARED
    GraffitiJNI.cpp
    MobileGS.cpp
    StereoProcessor.cpp
)

# Include directories
target_include_directories(graffitixr PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${GLM_DIR}
    ${OpenCV_INCLUDE_DIRS}
)

# Find required Android libraries
find_library(log-lib log)

# Link libraries - OpenGL ES 3.0 for splat rendering
target_link_libraries(graffitixr
    ${log-lib}
    ${OpenCV_LIBS}
    GLESv3
    EGL
    android
)
