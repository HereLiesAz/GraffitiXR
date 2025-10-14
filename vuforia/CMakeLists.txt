cmake_minimum_required(VERSION 3.18.1)

project(VuforiaWrapper)

add_library(VuforiaEngine SHARED IMPORTED)

set_target_properties(VuforiaEngine PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/sdk/lib/${ANDROID_ABI}/libVuforiaEngine.so"
)

# Expose the include directory for consumers of this library
target_include_directories(VuforiaEngine INTERFACE
    "${CMAKE_CURRENT_SOURCE_DIR}/sdk/include"
)
