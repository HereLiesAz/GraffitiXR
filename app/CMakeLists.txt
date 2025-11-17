cmake_minimum_required(VERSION 3.22.1)

project("graffitixr")

add_library(
        graffitixr
        SHARED
        src/main/cpp/GraffitiJNI.cpp)

find_library(
        log-lib
        log)

target_link_libraries(
        graffitixr
        ${log-lib})
