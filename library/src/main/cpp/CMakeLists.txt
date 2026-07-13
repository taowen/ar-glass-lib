cmake_minimum_required(VERSION 3.22.1)
project(ar_glass)

add_library(ar_glass STATIC ar_glass.cpp)
target_include_directories(ar_glass PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_features(ar_glass PUBLIC cxx_std_20)

# JNI is a thin adapter around the reusable native target above. arctrl can
# add_subdirectory(this directory) and link ar_glass directly (white-box use).
option(AR_GLASS_BUILD_JNI "Build the standalone JNI adapter" ON)
if(AR_GLASS_BUILD_JNI)
    add_library(ar_glass_jni SHARED ar_glass_jni.cpp)
    set_target_properties(ar_glass_jni PROPERTIES OUTPUT_NAME ar_glass)
    target_link_libraries(ar_glass_jni PRIVATE ar_glass)
endif()
