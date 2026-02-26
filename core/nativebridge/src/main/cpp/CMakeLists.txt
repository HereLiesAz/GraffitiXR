cmake_minimum_required(VERSION 3.22.1)

project("graffitixr_native")

# Set the OpenCV directory.
# This assumes OpenCV is located in a 'libs' folder at the root of your project.
set(OPENCV_SDK_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../libs/opencv/sdk/native/jni)
set(OpenCV_DIR ${OPENCV_SDK_DIR})

# Find OpenCV package
find_package(OpenCV REQUIRED)

add_library(graffitixr_native SHARED
    GraffitiJNI.cpp
    VulkanBackend.cpp
    MobileGS.cpp
    StereoProcessor.cpp
)

# Include directories
target_include_directories(graffitixr_native PRIVATE
    include
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/glm
    ${OpenCV_INCLUDE_DIRS}
)

# Link necessary Android, Vulkan, and OpenCV libraries
find_library(log-lib log)
find_library(android-lib android)
find_library(vulkan-lib vulkan)

target_link_libraries(graffitixr_native
    ${log-lib}
    ${android-lib}
    ${vulkan-lib}
    ${OpenCV_LIBS}
)