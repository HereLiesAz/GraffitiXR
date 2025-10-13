# Sets the minimum version of CMake required to build the native library.
cmake_minimum_required(VERSION 3.22.1)

# Declares the project name.
project("graffitixr")

# Find and link Vuforia using prefab
find_package(vuforia REQUIRED CONFIG)

# Adds the C++ source file to the library.
add_library(
        graffitixr
        SHARED
        src/main/cpp/GraffitiJNI.cpp)

# Finds and links the Android logging library.
find_library(
        log-lib
        log)

# Links the libraries.
target_link_libraries(
        graffitixr
        ${log-lib}
        vuforia::VuforiaEngine)
