# Sets the minimum version of CMake required to build the native library.
cmake_minimum_required(VERSION 3.18.1)

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

# Specifies the path to the pre-compiled Vuforia Engine library.
set(VUFORIA_LIBRARY_DIR ${CMAKE_SOURCE_DIR}/../vuforia/lib/${ANDROID_ABI})
add_library(VuforiaEngine SHARED IMPORTED)
set_target_properties(VuforiaEngine PROPERTIES IMPORTED_LOCATION
        ${VUFORIA_LIBRARY_DIR}/libVuforiaEngine.so)

# Specifies the include directories.
include_directories(${CMAKE_SOURCE_DIR}/../vuforia/include)

# Links the libraries.
target_link_libraries(
        graffitixr
        ${log-lib}
        VuforiaEngine)
