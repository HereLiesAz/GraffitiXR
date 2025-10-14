cmake_minimum_required(VERSION 3.22.1)

project("graffitixr")

add_library(
        graffitixr
        SHARED
        src/main/cpp/GraffitiJNI.cpp)

find_library(
        log-lib
        log)

add_library(Vuforia SHARED IMPORTED)
set_target_properties(Vuforia PROPERTIES IMPORTED_LOCATION
        ${CMAKE_CURRENT_SOURCE_DIR}/../vuforia/src/main/jniLibs/${ANDROID_ABI}/libVuforia.so)

target_link_libraries(
        graffitixr
        ${log-lib}
        Vuforia)

target_include_directories(graffitixr PRIVATE ../vuforia/sdk/include)
