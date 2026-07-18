cmake_minimum_required(VERSION 3.22.1)
project(ar_glass)

add_library(ar_glass STATIC ar_glass.cpp)
target_include_directories(ar_glass PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_features(ar_glass PUBLIC cxx_std_20)

# JNI is a thin adapter around the reusable native target above. arctrl can
# add_subdirectory(this directory) and link ar_glass directly (white-box use).
option(AR_GLASS_BUILD_JNI "Build the standalone JNI adapter" ON)
if(AR_GLASS_BUILD_JNI)
    set(LIBUSB_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/libusb)
    add_library(ar_glass_libusb SHARED
        ${LIBUSB_ROOT}/libusb/core.c
        ${LIBUSB_ROOT}/libusb/descriptor.c
        ${LIBUSB_ROOT}/libusb/hotplug.c
        ${LIBUSB_ROOT}/libusb/io.c
        ${LIBUSB_ROOT}/libusb/sync.c
        ${LIBUSB_ROOT}/libusb/strerror.c
        ${LIBUSB_ROOT}/libusb/os/linux_usbfs.c
        ${LIBUSB_ROOT}/libusb/os/events_posix.c
        ${LIBUSB_ROOT}/libusb/os/threads_posix.c
        ${LIBUSB_ROOT}/libusb/os/linux_netlink.c)
    target_include_directories(ar_glass_libusb PRIVATE ${LIBUSB_ROOT}/android ${LIBUSB_ROOT}/libusb ${LIBUSB_ROOT}/libusb/os)
    target_compile_definitions(ar_glass_libusb PRIVATE OS_LINUX=1)
    target_compile_options(ar_glass_libusb PRIVATE -pthread -fvisibility=hidden)
    target_link_libraries(ar_glass_libusb PRIVATE log)
    add_library(ar_glass_jni SHARED ar_glass_jni.cpp beast_uvc_jni.cpp)
    target_include_directories(ar_glass_jni PRIVATE ${LIBUSB_ROOT}/libusb)
    set_target_properties(ar_glass_jni PROPERTIES OUTPUT_NAME ar_glass)
    target_link_libraries(ar_glass_jni PRIVATE ar_glass ar_glass_libusb log)
endif()
