cmake_minimum_required(VERSION 3.22.1)

project("graffiti_slam")

# Define the shared library
add_library(
        graffiti_slam
        SHARED
        GraffitiJNI.cpp
)

# Search for system libraries
find_library(
        log-lib
        log
)

# --- Configuration for ORB-SLAM3 Integration ---
# Note: To fully enable ORB-SLAM3, you must:
# 1. Download ORB_SLAM3 source code.
# 2. Place it in app/src/main/cpp/ORB_SLAM3
# 3. Ensure dependencies (Eigen3, OpenCV, Pangolin) are available.
#    - OpenCV is assumed to be imported via Android Archive or separate module.
#    - Eigen3 is header-only, can be included directly.
#    - Pangolin/DBoW2 needs to be built.

# Placeholder for Include Directories (Uncomment when sources are present)
# include_directories(
#     ${CMAKE_CURRENT_SOURCE_DIR}/ORB_SLAM3
#     ${CMAKE_CURRENT_SOURCE_DIR}/ORB_SLAM3/include
#     ${CMAKE_CURRENT_SOURCE_DIR}/ORB_SLAM3/Thirdparty/Sophus
# )

# Placeholder for adding ORB-SLAM3 source files
# file(GLOB_RECURSE ORB_SLAM_SOURCES "ORB_SLAM3/src/*.cc")

# Link libraries
target_link_libraries(
        graffiti_slam
        ${log-lib}
        # orb_slam3  <-- Add this when built
        # opencv_java4 <-- Add this if using shared opencv
)
