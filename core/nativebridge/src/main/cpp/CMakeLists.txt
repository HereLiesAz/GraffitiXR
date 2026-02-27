cmake_minimum_required(VERSION 3.22.1)

project("graffitixr_native")

# Set the OpenCV directory.
# This assumes OpenCV is located in a 'libs' folder at the root of your project.
set(OPENCV_SDK_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/opencv/sdk/native/jni/)
set(OpenCV_DIR ${OPENCV_SDK_DIR})

# Find OpenCV package
find_package(OpenCV REQUIRED)

# --- Shader Compilation ---
# Locate glslc from the Android NDK's bundled shaderc.
find_program(GLSLC glslc HINTS
    "$ENV{ANDROID_NDK}/shader-tools/${ANDROID_HOST_TAG}"
    "$ENV{ANDROID_NDK_HOME}/shader-tools/${ANDROID_HOST_TAG}"
)

set(SHADER_SRC_DIR ${CMAKE_CURRENT_SOURCE_DIR}/shaders)
set(SHADER_ASSET_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../assets/shaders)
file(MAKE_DIRECTORY ${SHADER_ASSET_DIR})

if(GLSLC)
    set(VERT_SPV ${SHADER_ASSET_DIR}/splat.vert.spv)
    set(FRAG_SPV ${SHADER_ASSET_DIR}/splat.frag.spv)

    add_custom_command(
        OUTPUT ${VERT_SPV}
        COMMAND ${GLSLC} ${SHADER_SRC_DIR}/splat.vert -o ${VERT_SPV}
        DEPENDS ${SHADER_SRC_DIR}/splat.vert
        COMMENT "Compiling splat.vert -> splat.vert.spv"
    )
    add_custom_command(
        OUTPUT ${FRAG_SPV}
        COMMAND ${GLSLC} ${SHADER_SRC_DIR}/splat.frag -o ${FRAG_SPV}
        DEPENDS ${SHADER_SRC_DIR}/splat.frag
        COMMENT "Compiling splat.frag -> splat.frag.spv"
    )
    add_custom_target(compile_shaders ALL DEPENDS ${VERT_SPV} ${FRAG_SPV})
else()
    message(WARNING "glslc not found — expecting pre-compiled .spv files in ${SHADER_ASSET_DIR}")
endif()
# --- End Shader Compilation ---

add_library(graffitixr_native SHARED
    GraffitiJNI.cpp
    VulkanBackend.cpp
    MobileGS.cpp
    StereoProcessor.cpp
)

if(GLSLC)
    add_dependencies(graffitixr_native compile_shaders)
endif()

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
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(graffitixr_native
    ${log-lib}
    ${android-lib}
    ${vulkan-lib}
    ${jnigraphics-lib}
    ${OpenCV_LIBS}
)