# Sets the minimum version of CMake required to build the native library.
cmake_minimum_required(VERSION 3.22.1)

# Declares the project name.
project("graffitixr")

# Adds the C++ source file to the library.
add_library(
        graffitixr
        SHARED
        src/main/cpp/GraffitiJNI.cpp)

# Finds and links the Android logging library.
find_library(
        log-lib
        log)

# Adds the Vuforia library as a pre-compiled dependency.
add_library(VuforiaEngine SHARED IMPORTED)
set_target_properties(VuforiaEngine PROPERTIES IMPORTED_LOCATION
        "${Vuforia_LIBRARY_DIR}/${ANDROID_ABI}/libVuforiaEngine.so")

# Specifies the include directories.
include_directories(${Vuforia_INCLUDE_DIR})

# Links the libraries.
target_link_libraries(
        graffitixr
        ${log-lib}
        VuforiaEngine)
