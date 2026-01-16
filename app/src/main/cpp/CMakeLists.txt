cmake_minimum_required(VERSION 3.18.1)

project("graffitixr")

# --- 1. OpenCV Configuration ---
if (NOT OpenCV_DIR)
    if (EXISTS "${CMAKE_SOURCE_DIR}/../../../../libs/opencv/sdk/native/jni")
        set(OpenCV_DIR "${CMAKE_SOURCE_DIR}/../../../../libs/opencv/sdk/native/jni")
    elseif (EXISTS "${CMAKE_SOURCE_DIR}/../../../libs/opencv/sdk/native/jni")
        set(OpenCV_DIR "${CMAKE_SOURCE_DIR}/../../../libs/opencv/sdk/native/jni")
    elseif (EXISTS "${CMAKE_SOURCE_DIR}/../../../../opencv/sdk/native/jni")
        set(OpenCV_DIR "${CMAKE_SOURCE_DIR}/../../../../opencv/sdk/native/jni")
    endif()
endif()

message(STATUS "OpenCV_DIR: ${OpenCV_DIR}")
find_package(OpenCV REQUIRED)

# --- 2. GLM Configuration (Auto-Download) ---
include(FetchContent)
FetchContent_Declare(
    glm
    GIT_REPOSITORY https://github.com/g-truc/glm.git
    GIT_TAG 0.9.9.8
)
FetchContent_MakeAvailable(glm)

# --- Include Directories ---
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/include)
include_directories(${OpenCV_INCLUDE_DIRS})
# GLM include (usually handled by target_link_libraries, but adding explicitly to be safe)
include_directories(${glm_SOURCE_DIR})

# --- Source Files ---
set(SOURCE_FILES
    GraffitiJNI.cpp
    MobileGS.cpp
)

# --- Native Library ---
add_library(graffiti-lib SHARED ${SOURCE_FILES})

# --- System Libraries ---
find_library(log-lib log)
find_library(android-lib android)
find_library(gles3-lib GLESv3)
find_library(egl-lib EGL)

# --- Linking ---
target_link_libraries(graffiti-lib
        ${OpenCV_LIBS}
        glm::glm
        ${log-lib}
        ${android-lib}
        ${gles3-lib}
        ${egl-lib})