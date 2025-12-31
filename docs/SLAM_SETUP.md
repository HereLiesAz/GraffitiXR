# ORB-SLAM3 Integration Guide

This project is set up to integrate **ORB-SLAM3** for advanced visual SLAM capabilities. Due to the size and licensing (GPLv3) of ORB-SLAM3, the source code is not included directly in this repository's main branch.

## Setup Instructions

To fully enable SLAM functionality, follow these steps:

1.  **Clone ORB-SLAM3**:
    Download the ORB-SLAM3 source code from [https://github.com/UZ-SLAMLab/ORB_SLAM3](https://github.com/UZ-SLAMLab/ORB_SLAM3).

2.  **Place Source Code**:
    Copy the `ORB_SLAM3` folder into `app/src/main/cpp/`.
    The path should look like: `app/src/main/cpp/ORB_SLAM3/`.

3.  **Install Dependencies**:
    *   **Eigen3**: Download Eigen3 headers and place them in `app/src/main/cpp/Eigen3`.
    *   **OpenCV**: The project already uses OpenCV for Android. Ensure the native libraries (`libopencv_java4.so`) are accessible to CMake.

4.  **Update CMakeLists.txt**:
    Open `app/src/main/cpp/CMakeLists.txt` and uncomment the sections related to ORB-SLAM3 includes and source files.

    ```cmake
    include_directories(
        ${CMAKE_CURRENT_SOURCE_DIR}/ORB_SLAM3
        ${CMAKE_CURRENT_SOURCE_DIR}/ORB_SLAM3/include
        ...
    )
    ```

5.  **Implement JNI Bridge**:
    Update `app/src/main/cpp/GraffitiJNI.cpp` to replace the "Stub" implementations with actual calls to the ORB-SLAM3 `System` object.

    ```cpp
    // Example
    SLAM = new ORB_SLAM3::System(vocFile, settingsFile, ORB_SLAM3::System::MONOCULAR, true);
    ```

## Licensing
Please note that linking against ORB-SLAM3 requires your application to comply with the **GPLv3** license.
