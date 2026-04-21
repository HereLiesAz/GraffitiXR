cmake_minimum_required(VERSION 3.22.1)

project("collaboration_bridge")

add_library(
        collaboration_bridge
        SHARED
        CollaborationBridge.cpp
)

find_library(
        log-lib
        log
)

target_link_libraries(
        collaboration_bridge
        ${log-lib}
)
